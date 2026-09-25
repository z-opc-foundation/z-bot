package com.zifang.z.bot.tool;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ApprovalService} 的每会话 FIFO + 四档决议单测（P11 工作项 3）。
 */
public class ApprovalServiceTest {

    private static final String S1 = "session-1";
    private static final String S2 = "session-2";

    /** 记录 ALWAYS 落盘调用的假实现（真实实现写 configDir，见 BotConfigApprovalPersistenceTest）。 */
    private static final class RecordingPersistence implements ApprovalService.Persistence {
        private final List<String> written = new ArrayList<String>();

        @Override
        public void appendAlwaysApproval(String approvalKey) {
            written.add(approvalKey);
        }
    }

    private ApprovalService service;
    private RecordingPersistence sink;

    @Before
    public void setUp() {
        service = new ApprovalService(Arrays.asList("git status"), Collections.<String>emptyList());
        sink = new RecordingPersistence();
        service.setPersistence(sink);
    }

    private ApprovalService.Request submit(String sessionKey, String command) {
        return service.submit(sessionKey, "exec", "{\"command\":\"" + command + "\"}", command,
                ExecGuard.approvalKey(command), "高危命令需要确认（测试）", "test-rule");
    }

    // ===== FIFO =====

    @Test
    public void requestsQueueUpInOrderPerSession() {
        ApprovalService.Request first = submit(S1, "rm -rf ./build");
        ApprovalService.Request second = submit(S1, "sudo ls");
        ApprovalService.Request third = submit(S1, "docker rm -f x");
        assertEquals(3, service.pendingCount(S1));
        assertSame(first, service.next(S1));
        List<ApprovalService.Request> pending = service.pending(S1);
        assertEquals(Arrays.asList(first, second, third), pending);
        // next() 不出队
        assertEquals(3, service.pendingCount(S1));
    }

    @Test
    public void resolveNextConsumesOldestFirst() {
        ApprovalService.Request first = submit(S1, "rm -rf ./a");
        ApprovalService.Request second = submit(S1, "rm -rf ./b");
        ApprovalService.Resolved r1 = service.resolveNext(S1, ApprovalService.Resolution.ONCE);
        assertSame(first, r1.request());
        ApprovalService.Resolved r2 = service.resolveNext(S1, ApprovalService.Resolution.ONCE);
        assertSame(second, r2.request());
        assertEquals(0, service.pendingCount(S1));
        // 空队列再来一次：不炸，request 为 null
        ApprovalService.Resolved r3 = service.resolveNext(S1, ApprovalService.Resolution.DENY);
        assertNull(r3.request());
        assertTrue(r3.denied());
    }

    @Test
    public void sessionsDoNotSeeEachOtherQueues() {
        ApprovalService.Request mine = submit(S1, "rm -rf ./a");
        submit(S2, "rm -rf ./b");
        assertEquals(1, service.pendingCount(S1));
        assertEquals(1, service.pendingCount(S2));
        assertEquals(2, service.sessionCountWithPending());
        assertSame(mine, service.next(S1));
        // 消费 S1 不影响 S2
        service.resolveNext(S1, ApprovalService.Resolution.DENY);
        assertEquals(0, service.pendingCount(S1));
        assertEquals(1, service.pendingCount(S2));
    }

    @Test
    public void resolveByIdConsumesThatEntryNotTheHead() {
        submit(S1, "rm -rf ./a");
        ApprovalService.Request second = submit(S1, "rm -rf ./b");
        ApprovalService.Resolved resolved = service.resolve(S1, second.id(), ApprovalService.Resolution.DENY);
        assertSame(second, resolved.request());
        assertEquals(1, service.pendingCount(S1));
        assertEquals("rm -rf ./a", service.next(S1).command());
        // 不存在的 id：不许把人的决议吞掉后什么都不做 —— 返回 null 请求，由调用方提示
        assertNull(service.resolve(S1, "no-such-id", ApprovalService.Resolution.ONCE).request());
    }

    @Test
    public void queueIsCappedAndDropsOldest() {
        service.setMaxQueuePerSession(3);
        submit(S1, "rm -rf ./1");
        submit(S1, "rm -rf ./2");
        submit(S1, "rm -rf ./3");
        submit(S1, "rm -rf ./4");
        assertEquals(3, service.pendingCount(S1));
        assertEquals("rm -rf ./2", service.next(S1).command());
    }

    @Test
    public void clearQueueAndForgetSessionDropPending() {
        submit(S1, "rm -rf ./a");
        service.clearQueue(S1);
        assertEquals(0, service.pendingCount(S1));
        submit(S1, "rm -rf ./a");
        service.forgetSession(S1);
        assertEquals(0, service.pendingCount(S1));
    }

    // ===== 四档决议 =====

    @Test
    public void onceLeavesNoTrace() {
        submit(S1, "rm -rf ./build");
        service.resolveNext(S1, ApprovalService.Resolution.ONCE);
        assertFalse(service.isApproved(S1, ExecGuard.approvalKey("rm -rf ./build")));
        assertTrue(sink.written.isEmpty());
        assertTrue(service.sessionApprovals(S1).isEmpty());
    }

    @Test
    public void sessionApprovalAppliesToSameCommandOnlyAndNeverHitsDisk() {
        submit(S1, "rm -rf ./build");
        service.resolveNext(S1, ApprovalService.Resolution.SESSION);
        String key = ExecGuard.approvalKey("rm -rf ./build");
        assertTrue(service.isApproved(S1, key));
        assertTrue(service.sessionApprovals(S1).contains(key));
        assertTrue("SESSION 档绝不落盘", sink.written.isEmpty());
        // 别的会话不共享
        assertFalse(service.isApproved(S2, key));
        // 前缀相同但不同的命令不共享（精确匹配）
        assertFalse(service.isApproved(S1, ExecGuard.approvalKey("rm -rf ./other")));
        // 会话结束即失效
        service.forgetSession(S1);
        assertFalse(service.isApproved(S1, key));
    }

    @Test
    public void alwaysIsTheOnlyResolutionThatPersists() {
        submit(S1, "docker rm -f old");
        service.resolveNext(S1, ApprovalService.Resolution.ALWAYS);
        String key = ExecGuard.approvalKey("docker rm -f old");
        assertEquals(Collections.singletonList(key), sink.written);
        assertTrue(service.isApproved(S1, key));
        assertTrue(service.isApproved(S2, key));
        assertEquals(Collections.singletonList(key), service.permanentApprovals());
    }

    @Test
    public void denyConsumesRequestAndExecutesNothing() {
        ApprovalService.Request request = submit(S1, "rm -rf ./build");
        ApprovalService.Resolved resolved = service.resolveNext(S1, ApprovalService.Resolution.DENY);
        assertTrue(resolved.denied());
        assertSame(request, resolved.request());
        assertEquals(0, service.pendingCount(S1));
        assertFalse(service.isApproved(S1, request.approvalKey()));
        assertTrue(sink.written.isEmpty());
    }

    @Test
    public void permanentApprovalsLoadFromConfigAtStartup() {
        ApprovalService withExisting = new ApprovalService(Collections.<String>emptyList(),
                Arrays.asList("git push origin main", "  docker rm -f old  ", ""));
        assertTrue(withExisting.isApproved(S1, "git push origin main"));
        assertTrue("条目两侧空白要 trim", withExisting.isApproved(S2, "docker rm -f old"));
        assertEquals(2, withExisting.permanentApprovals().size());
    }

    @Test
    public void alwaysApprovalStillCannotBeatHardline() {
        // 红线 7：即使有人把 rm -rf / 写进 always 名单，decide 也照拒
        service.approvePermanent(ExecGuard.approvalKey("rm -rf /"));
        String command = "rm -rf /";
        assertTrue(service.isApproved(S1, ExecGuard.approvalKey(command)));
        assertEquals(ExecGuard.Verdict.DENY_HARDLINE, ExecGuard.decide(ExecGuard.MODE_ALL, command,
                false, Arrays.asList(command)).verdict());
        // 盖章也白搭
        assertEquals(ExecGuard.Verdict.DENY_HARDLINE,
                ExecGuard.decide(ExecGuard.MODE_DANGEROUS, command, true, null).verdict());
    }

    @Test
    public void sessionApprovalDoesNotMakeADifferentCommandPassDecide() {
        service.approveSession(S1, ExecGuard.approvalKey("rm -rf ./build"));
        // 已批准的这条：精确放行后 decide 走 ALLOW（because 人已经看过一模一样的命令）
        assertEquals(ExecGuard.Verdict.ALLOW, ExecGuard.decide(ExecGuard.MODE_DANGEROUS,
                "rm -rf ./build", service.isApproved(S1, ExecGuard.approvalKey("rm -rf ./build")), null).verdict());
        // 只改一个字符：必须重新问人
        assertEquals(ExecGuard.Verdict.NEED_APPROVAL, ExecGuard.decide(ExecGuard.MODE_DANGEROUS,
                "rm -rf ./buildd", service.isApproved(S1, ExecGuard.approvalKey("rm -rf ./buildd")), null).verdict());
    }

    // ===== Resolution.parse =====

    @Test
    public void resolutionParsingIsConservative() {
        assertEquals(ApprovalService.Resolution.ONCE, ApprovalService.Resolution.parse("once", null));
        assertEquals(ApprovalService.Resolution.ONCE, ApprovalService.Resolution.parse("YES", null));
        assertEquals(ApprovalService.Resolution.SESSION, ApprovalService.Resolution.parse(" s ", null));
        assertEquals(ApprovalService.Resolution.ALWAYS, ApprovalService.Resolution.parse("ALWAYS", null));
        assertEquals(ApprovalService.Resolution.ALWAYS, ApprovalService.Resolution.parse("a", null));
        assertEquals(ApprovalService.Resolution.DENY, ApprovalService.Resolution.parse("no", null));
        // 空白才用缺省值；认不出的文字一律 DENY（宁可少放行）
        assertEquals(ApprovalService.Resolution.SESSION, ApprovalService.Resolution.parse("  ",
                ApprovalService.Resolution.SESSION));
        assertEquals("认不出的文字不许当批准", ApprovalService.Resolution.DENY,
                ApprovalService.Resolution.parse("sure?sounds good", null));
        // null/空白 = 人没给档位 ⇒ 用缺省值（裸 /confirm 的语义就是 once），这不是"猜批准"
        assertEquals(ApprovalService.Resolution.ONCE, ApprovalService.Resolution.parse(null, null));
        assertEquals(ApprovalService.Resolution.DENY, ApprovalService.Resolution.parse("", ApprovalService.Resolution.DENY));
        assertEquals(ApprovalService.Resolution.DENY, ApprovalService.Resolution.parse("approve-all", null));
    }

    // ===== 会话键绑定与白名单读取 =====

    @Test
    public void sessionKeyComesFromBoundSupplier() {
        final String[] current = {S1};
        service.bindSessionKey(new java.util.function.Supplier<String>() {
            @Override
            public String get() {
                return current[0];
            }
        });
        assertEquals(S1, service.currentSessionKey());
        submit(service.currentSessionKey(), "rm -rf ./a");
        assertEquals(1, service.pending().size());
        current[0] = S2;
        assertEquals(0, service.pending().size());
        assertEquals(S2, service.currentSessionKey());
        // 会话 id 拿不到时回落 default，而不是 NPE
        service.bindSessionKey(null);
        assertEquals(ApprovalService.DEFAULT_SESSION_KEY, service.currentSessionKey());
    }

    @Test
    public void prefixWhitelistIsTrimmedAndReadable() {
        service.setPrefixWhitelist(Arrays.asList("  git status  ", "", null, "ls -l"));
        assertEquals(Arrays.asList("git status", "ls -l"), service.prefixWhitelist());
    }

    @Test
    public void requestCarriesEverythingTheHumanNeedsToJudge() {
        ApprovalService.Request request = submit(S1, "sudo systemctl restart nginx");
        assertEquals("exec", request.toolName());
        assertEquals("sudo systemctl restart nginx", request.command());
        assertNotNull(request.argsJson());
        assertTrue(request.reason().contains("高危命令需要确认"));
        assertEquals("test-rule", request.rule());
        assertEquals(S1, request.sessionKey());
        assertTrue(request.createdAtMillis() > 0);
        assertEquals(ExecGuard.approvalKey("sudo systemctl restart nginx"), request.approvalKey());
    }
}
