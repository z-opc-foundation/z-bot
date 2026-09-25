package com.zifang.z.bot.config;

import com.zifang.z.bot.tool.ApprovalService;
import com.zifang.z.bot.tool.ExecGuard;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ALWAYS 档落盘（工作项 3）：只写 configDir、保留其它行、命令里带逗号也能原样读回。
 *
 * <p>全部写在 {@link TemporaryFolder} 里 —— 单测绝不碰 {@code ~/.zbot}。</p>
 */
public class BotConfigApprovalPersistenceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String OTHER_KEYS = "agent.name=zifa\nagent.model=claude-test\n";

    private File writeConfig(String body) throws Exception {
        File dir = folder.newFolder("cfg");
        File file = new File(dir, "config.properties");
        java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), "UTF-8");
        try {
            w.write(body);
        } finally {
            w.close();
        }
        return dir;
    }

    private static String readAll(File dir) throws Exception {
        File file = new File(dir, "config.properties");
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    @Test
    public void alwaysApprovalsAreParsedFromConfig() throws Exception {
        File dir = writeConfig(OTHER_KEYS + "agent.exec.approval.always=git status,docker rm -f old\n");
        BotConfig config = BotConfig.load(dir);
        assertEquals(Arrays.asList("git status", "docker rm -f old"), config.getExecAlwaysApprovals());
    }

    @Test
    public void missingKeyMeansEmptyList() throws Exception {
        BotConfig config = BotConfig.load(writeConfig(OTHER_KEYS));
        assertTrue(config.getExecAlwaysApprovals().isEmpty());
    }

    @Test
    public void appendWritesOnlyIntoConfigDirAndKeepsOtherLines() throws Exception {
        File dir = writeConfig("# 我的配置\n" + OTHER_KEYS + "\n"
                + "agent.exec.confirm.whitelist=git status,ls -l\n");
        BotConfig config = BotConfig.load(dir);
        String before = readAll(dir);
        int entriesBefore = dir.list().length;

        assertTrue(config.appendAlwaysApproval("rm -rf ./build"));

        String after = readAll(dir);
        assertTrue("其它内容必须逐字保留:\n" + after, after.contains("# 我的配置"));
        assertTrue(after.contains("agent.name=zifa"));
        assertTrue(after.contains("agent.model=claude-test"));
        assertTrue(after.contains("agent.exec.confirm.whitelist=git status,ls -l"));
        assertTrue(after.contains("agent.exec.approval.always=rm -rf ./build"));
        // 红线 1：configDir 里只多出/改动 config.properties 这一个文件，别的一律不碰
        assertEquals(entriesBefore, dir.list().length);
        assertFalse("临时文件必须已被改名掉", new File(dir, "config.properties.zbot-tmp").exists());
        assertEquals(before, after.replace("agent.exec.approval.always=rm -rf ./build\n", ""));
    }

    @Test
    public void appendIsIdempotentAndAccumulates() throws Exception {
        BotConfig config = BotConfig.load(writeConfig(OTHER_KEYS));
        assertTrue(config.appendAlwaysApproval("docker rm -f old"));
        assertTrue(config.appendAlwaysApproval("git push origin main"));
        assertTrue("重复追加不产生第二条", config.appendAlwaysApproval("docker rm -f old"));
        assertEquals(Arrays.asList("docker rm -f old", "git push origin main"),
                config.getExecAlwaysApprovals());
        int occurrences = readAll(config.getConfigDir()).split("agent\\.exec\\.approval\\.always=", -1).length - 1;
        assertEquals("同一键只应有一行", 1, occurrences);
    }

    @Test
    public void commandsWithCommasSurviveRoundTrip() throws Exception {
        File dir = writeConfig(OTHER_KEYS);
        BotConfig config = BotConfig.load(dir);
        List<String> commands = Arrays.asList(
                "docker run -e A=1,B=2 alpine",
                "awk -F, '{print $1}' x.csv",
                "git log --pretty=format:%h,%s",
                "C:\\path\\to\\tool --x",
                "ls -l\nrm -rf ./build");
        for (String command : commands) {
            assertTrue(command, config.appendAlwaysApproval(command));
        }
        // 重新从盘上读：必须一模一样，不能被逗号切开（Properties 会吃掉一层反斜杠，这是实测出的坑）
        BotConfig reloaded = BotConfig.load(dir);
        assertEquals(commands, reloaded.getExecAlwaysApprovals());
        for (String command : commands) {
            assertEquals(ExecGuard.approvalKey(command),
                    reloaded.getExecAlwaysApprovals().get(commands.indexOf(command)));
        }
    }

    @Test
    public void blankAndNullAppendsAreRejected() throws Exception {
        BotConfig config = BotConfig.load(writeConfig(OTHER_KEYS));
        assertFalse(config.appendAlwaysApproval(null));
        assertFalse(config.appendAlwaysApproval("   "));
        assertTrue(config.getExecAlwaysApprovals().isEmpty());
    }

    @Test
    public void serviceAndConfigAgreeOnWhatLandsOnDisk() throws Exception {
        // 端到端串一遍：ApprovalService 的 ALWAYS → BotConfig 落盘 → 重启后仍然免问
        File dir = writeConfig(OTHER_KEYS);
        BotConfig config = BotConfig.load(dir);
        ApprovalService service = new ApprovalService(config.getExecWhitelist(),
                config.getExecAlwaysApprovals());
        service.setPersistence(new ApprovalService.Persistence() {
            @Override
            public void appendAlwaysApproval(String approvalKey) {
                config.appendAlwaysApproval(approvalKey);
            }
        });
        service.submit("s-1", "exec", "{\"command\":\"docker rm -f old\"}", "docker rm -f old",
                ExecGuard.approvalKey("docker rm -f old"), "高危命令需要确认", "docker 生命周期");
        service.resolveNext("s-1", ApprovalService.Resolution.ALWAYS);

        BotConfig restarted = BotConfig.load(dir);
        assertEquals(Arrays.asList("docker rm -f old"), restarted.getExecAlwaysApprovals());
        ApprovalService restartedService = new ApprovalService(restarted.getExecWhitelist(),
                restarted.getExecAlwaysApprovals());
        assertTrue("重启后 ALWAYS 档仍免问", restartedService.isApproved("other-session",
                ExecGuard.approvalKey("docker rm -f old")));
        // 而 SESSION 档不会走到这里：它不落盘
        service.resolveNext("s-1", ApprovalService.Resolution.SESSION);
        assertEquals(Arrays.asList("docker rm -f old"), BotConfig.load(dir).getExecAlwaysApprovals());
    }

    @Test
    public void prefixWhitelistKeyStaysSeparateFromAlwaysList() throws Exception {
        File dir = writeConfig(OTHER_KEYS + "agent.exec.confirm.whitelist=git status\n"
                + "agent.exec.approval.always=docker rm -f old\n");
        BotConfig config = BotConfig.load(dir);
        assertEquals(Arrays.asList("git status"), config.getExecWhitelist());
        assertEquals(Arrays.asList("docker rm -f old"), config.getExecAlwaysApprovals());
    }
}
