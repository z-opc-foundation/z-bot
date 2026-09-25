package com.zifang.z.bot.config;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.ZBot;
import com.zifang.z.bot.center.BotCenterClient;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 红线 1（profile 隔离）的守卫集。
 *
 * <p>这条红线反复破的原因不是没人写 {@code --config-dir}，而是「数据目录缺省值」被抄了
 * 六份，每份都写死 {@code user.home + "/.zbot"} —— 于是 {@code --config-dir} 只管到
 * config.properties 和 state.db，workspace / sessions / skills / pairing 仍旧一起回到
 * 真实 home。所以这里盯的不是「有没有开关」，而是<strong>缺省值本身跟不跟着 profile 走</strong>。</p>
 *
 * <p>{@code ZBOT_HOME} 此前只在 {@code PairCommand} 的报错文案里出现过，全仓没有一处真的读它；
 * 现在它是 {@link BotConfig#defaultConfigDir()} 的第二档，第一档 {@code -Dzbot.home}
 * 同时充当单测注入 profile 的口子（进程内改不了环境变量）。</p>
 */
public class ProfileIsolationTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final List<String> propsToRestore = new ArrayList<String>();
    private ByteArrayOutputStream captured;
    private PrintStream realOut;

    @Before
    public void setUp() throws Exception {
        realOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, "UTF-8"));
    }

    @After
    public void tearDown() {
        System.setOut(realOut);
        for (String key : propsToRestore) {
            System.clearProperty(key);
        }
        propsToRestore.clear();
    }

    private void prop(String key, String value) {
        propsToRestore.add(key);
        System.setProperty(key, value);
    }

    private void clearProp(String key) {
        propsToRestore.add(key);
        System.clearProperty(key);
    }

    private File profile() throws Exception {
        return folder.newFolder("profile" + System.nanoTime());
    }

    private String out() {
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 真实 home 下的 profile 目录名快照 —— 用来证明整段测试没往真 profile 里落东西。 */
    private List<String> realHomeEntries() {
        String[] names = new File(System.getProperty("user.home"), ".zbot").list();
        List<String> list = names == null
                ? new ArrayList<String>() : new ArrayList<String>(Arrays.asList(names));
        Collections.sort(list);
        return list;
    }

    private static String row(String output, String key) {
        for (String line : output.split("\n")) {
            if (line.trim().startsWith(key + " ")) {
                return line.trim().substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    // ===== 缺省 profile 的解析 =====

    @Test
    public void zbotHomePropertyIsTheProfileDefault() throws Exception {
        File home = profile();
        prop("zbot.home", home.getAbsolutePath());

        assertEquals(home, BotConfig.defaultConfigDir());
        assertEquals("load() 必须读同一个 profile",
                home, BotConfig.load().getConfigDir());
    }

    @Test
    public void absentProfileFallsBackToHomeDotZbot() throws Exception {
        File expected = new File(System.getProperty("user.home"), ".zbot");
        assertEquals("没有 -Dzbot.home / ZBOT_HOME 时仍应是 ~/.zbot（老行为不变）",
                expected.getCanonicalPath(), BotConfig.defaultConfigDir().getCanonicalPath());
    }

    /** 数据目录的每一类都要跟着 profile，且彼此不共用一个写死的字面量。 */
    @Test
    public void everyDataDirFollowsTheProfile() throws Exception {
        File home = profile();
        prop("zbot.home", home.getAbsolutePath());
        BotConfig config = BotConfig.load();

        assertEquals(new File(home, "sessions"), config.sessionsDir());
        assertEquals(new File(home, "workspace"), config.workspaceDir());
        assertEquals(new File(home, "state.db").getAbsolutePath(), config.getStateDbPath());

        assertEquals("SessionManager 缺省档也在 profile 里",
                new File(home, "sessions"), new SessionManager().getSessionDir());
        assertEquals("Sandbox 空根回落也在 profile 里",
                new File(home, "workspace"), new Sandbox(null).root());
    }

    // ===== 沙箱根的优先级阶梯 =====

    @Test
    public void workspaceResolutionIsCliThenSysPropThenProfile() throws Exception {
        File home = profile();
        File cli = folder.newFolder("cli-sandbox");
        File sys = folder.newFolder("sysprop-sandbox");

        prop("zbot.sandbox", sys.getAbsolutePath());
        assertEquals("--sandbox 必须压过 -Dzbot.sandbox",
                cli, BotConfig.resolveWorkspaceDir(home, cli.getAbsolutePath()));

        prop("zbot.sandbox", sys.getAbsolutePath());
        assertEquals("没有 --sandbox 时 -Dzbot.sandbox 生效",
                sys, BotConfig.resolveWorkspaceDir(home, "  "));

        clearProp("zbot.sandbox");
        assertEquals("两个都不给才回落到 <profile>/workspace",
                new File(home, "workspace"), BotConfig.resolveWorkspaceDir(home, null));
    }

    /** 换 profile 时两类目录一起搬；显式沙箱开关不许牵动会话目录（反之亦然）。 */
    @Test
    public void eachSwitchAloneMovesOnlyItsOwnDir() throws Exception {
        File home = profile();
        File other = profile();
        File cli = folder.newFolder("cli-sb");
        BotConfig config = BotConfig.load(home);

        assertEquals("不给任何沙箱开关时，沙箱跟着 profile",
                new File(home, "workspace"), BotConfig.resolveWorkspaceDir(home, null));
        assertEquals("显式 --sandbox 只搬沙箱",
                cli, BotConfig.resolveWorkspaceDir(home, cli.getAbsolutePath()));
        assertEquals("--sandbox 不该牵动会话目录",
                new File(home, "sessions"), config.sessionsDir());
        assertEquals("--sandbox 不该牵动 state.db",
                new File(home, "state.db").getAbsolutePath(), config.getStateDbPath());

        File sysSandbox = folder.newFolder("sysprop-sb");
        prop("zbot.sandbox", sysSandbox.getAbsolutePath());
        assertEquals("-Dzbot.sandbox 只搬沙箱",
                sysSandbox, BotConfig.resolveWorkspaceDir(home, null));
        assertEquals("-Dzbot.sandbox 不该牵动会话目录",
                new File(home, "sessions"), config.sessionsDir());

        clearProp("zbot.sandbox");
        BotConfig moved = BotConfig.load(other);
        assertEquals("换 profile ⇒ 沙箱与会话一起搬",
                Arrays.asList(new File(other, "workspace"), new File(other, "sessions")),
                Arrays.asList(BotConfig.resolveWorkspaceDir(other, null), moved.sessionsDir()));
    }

    // ===== 装配面（Builder 不接 CLI 时的缺省） =====

    @Test
    public void agentBuilderKeepsEveryArtifactInsideTheProfile() throws Exception {
        File home = profile();
        prop("zbot.home", home.getAbsolutePath());
        writeConfig(home, "agent.delegate.max.depth=0\n");

        BotAgent agent = BotAgent.builder(BotConfig.load(home)).withoutCenter().build();

        String inside = home.getAbsolutePath() + File.separator;
        assertTrue("沙箱落到了 profile 外: " + agent.getSandbox().root(),
                agent.getSandbox().root().getAbsolutePath().startsWith(inside));
        assertTrue("会话目录落到了 profile 外: " + agent.getSessionManager().getSessionDir(),
                agent.getSessionManager().getSessionDir().getAbsolutePath().startsWith(inside));
        assertTrue("state.db 落到了 profile 外",
                new File(home, "state.db").exists());
    }

    /**
     * 命令行 {@code status} 是用户判断「我这个 profile 到底在用哪些目录」的唯一出口，
     * 所以它报的路径本身就是守卫：任何一行回到真实 home 就是红线 1 又破了。
     */
    @Test
    public void statusCommandReportsProfilePathsNotRealHome() throws Exception {
        File home = profile();
        List<String> before = realHomeEntries();

        int rc = new CommandLine(ZBot.class).execute("status", "--config-dir", home.getAbsolutePath());
        assertEquals(out(), 0, rc);

        String sandbox = row(out(), "sandbox");
        String sessions = row(out(), "sessions");
        assertTrue("status 的 sandbox 行不在 profile 里: " + sandbox,
                sandbox != null && sandbox.startsWith(home.getAbsolutePath()));
        assertTrue("status 的 sessions 行不在 profile 里: " + sessions,
                sessions != null && sessions.startsWith(home.getAbsolutePath()));
        assertFalse("status 输出里仍有写死的 ~/.zbot: " + out(),
                out().contains(System.getProperty("user.home") + "/.zbot"));
        assertEquals("跑一次 status 就改动了真实 profile: " + before + " -> " + realHomeEntries(),
                before, realHomeEntries());
    }

    /**
     * center 侧的 instance.json / skills 也归 profile。
     *
     * <p>{@code BotCenterClient} 以前有个单参构造器，自己猜 {@code ~/.zbot}；现在目录由调用方
     * 给（{@code BotAgent.Builder} 传的就是 {@code config.getConfigDir()}）。这里钉住"给了
     * profile 就一定落在 profile 里"，构造期不发网络请求（注册发生在 {@code register()}）。</p>
     */
    @Test
    public void centerClientWritesItsArtifactsIntoTheProfile() throws Exception {
        File home = profile();
        BotCenterClient center = new BotCenterClient("http://127.0.0.1:1", home);

        assertTrue("skills 根跑到了 profile 外: " + center.skillsRoot(),
                center.skillsRoot().getAbsolutePath()
                        .startsWith(home.getAbsolutePath() + File.separator));
    }

    // ===== helpers =====

    private static void writeConfig(File dir, String body) throws Exception {
        try (Writer w = Files.newBufferedWriter(new File(dir, "config.properties").toPath(),
                StandardCharsets.UTF_8)) {
            w.write(body);
        }
    }
}
