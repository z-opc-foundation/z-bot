package com.zifang.z.bot.tool;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

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
}
