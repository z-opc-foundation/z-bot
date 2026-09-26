package com.zifang.z.bot.channel;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link TurnLease}：租约必须按<b>解析后的 session_id</b> 串行，且四条安全性质各自成立：
 * 超时 fail-open、token 身份校验、线程身份校验、绝不逐出活租约。
 *
 * <p>{@link #unsynchronizedFlushOnSharedSessionWedgesTranscript()} 是<b>反面对照</b>：
 * 同一形状不加锁跑两轮，量出她 #64934 描述的现场（整读整写的 transcript 交织 ⇒ 落库出现连续两条
 * user）。它红了/失去意义，就说明「按 session_id 串行」根本没有可测的后果，租约是装饰。</p>
 */
public class TurnLeaseTest {

    /**
     * 一份 transcript，语义与 {@code SessionManager}/{@code StateStore} 的落库一致：
     * <b>逐条追加、按写入序持久化</b>（user 在轮首落、assistant 在轮尾落），读到的就是全量历史。
     * 所以两轮并发刷同一 session 的直接后果就是她 #64934 说的那个形状：连续两条 user。
     */
    private static final class Transcript {
        private final List<String> rows = Collections.synchronizedList(new ArrayList<String>());

        void append(String row) {
            rows.add(row);
        }

        List<String> read() {
            synchronized (rows) {
                return new ArrayList<String>(rows);
            }
        }

        int size() {
            return rows.size();
        }

        boolean hasConsecutiveUsers() {
            synchronized (rows) {
                for (int i = 1; i < rows.size(); i++) {
                    if (rows.get(i).startsWith("user") && rows.get(i - 1).startsWith("user")) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    /**
     * 一轮对话的骨架：装历史 → 落 user → 模拟一次 LLM 往返 → 落 assistant
     * （对应 {@code BotAgent.chat()} 的 {@code memory.add(user)} + {@code persistSession()}）。
     */
    private static void runTurn(TurnLease.SessionTurnLeaseRegistry reg, String sessionId,
            String ownerKey, long gen, Transcript t, boolean useLease, long waitMillis,
            AtomicReference<Throwable> firstError) {
        TurnLease.Token token = useLease
                ? reg.acquire(sessionId, ownerKey, Long.valueOf(gen), Long.valueOf(waitMillis)) : null;
        try {
            List<String> history = t.read();            // 装历史（本轮的基线）
            if (history.size() % 2 != 0) {
                firstError.compareAndSet(null, new AssertionError(
                        "第二轮读到的基线里缺了第一轮那一问一答: " + history));
            }
            t.append("user:" + ownerKey);
            sleep(30);
            t.append("assistant:" + ownerKey);
        } finally {
            if (useLease) {
                reg.release(token);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread turnThread(final TurnLease.SessionTurnLeaseRegistry reg, final String sid,
            final String key, final long gen, final Transcript t, final boolean useLease,
            final long waitMillis, final AtomicReference<Throwable> firstError) {
        return new Thread(new Runnable() {
            @Override public void run() {
                runTurn(reg, sid, key, gen, t, useLease, waitMillis, firstError);
            }
        });
    }

    // ===== 1. 多对一：两个路由键 → 同一 session_id 必须串行 =====

    @Test
    public void twoRouteKeysOnSameSessionIdSerializeAndKeepTranscriptAlternating() throws Exception {
        TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry();
        final Transcript t = new Transcript();
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        final String sid = "session_shared";
        Thread a = turnThread(reg, sid, "chanA:c1", 1L, t, true, 8000L, err);
        Thread b = turnThread(reg, sid, "chanB:c2", 2L, t, true, 8000L, err);
        a.start();
        sleep(5);
        b.start();
        a.join(20_000);
        b.join(20_000);

        assertFalse(a.isAlive() || b.isAlive());
        assertNull("带租约时第二轮读到的基线必须完整: " + t.read(), err.get());
        assertEquals("串行后 transcript 应是 4 行", 4, t.size());
        assertFalse("带租约不该出现连续两条 user: " + t.read(), t.hasConsecutiveUsers());
        assertNull("两轮都放完 ⇒ 该 session 无在飞持有者", reg.holderOf(sid));
    }

    @Test
    public void unsynchronizedFlushOnSharedSessionWedgesTranscript() throws Exception {
        TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry();
        final Transcript t = new Transcript();
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        final String sid = "session_shared";
        Thread a = turnThread(reg, sid, "chanA:c1", 1L, t, false, 0L, err);
        Thread b = turnThread(reg, sid, "chanB:c2", 2L, t, false, 0L, err);
        a.start();
        sleep(8);
        b.start();
        a.join(20_000);
        b.join(20_000);
        assertTrue("不加锁却没复现交织 ⇒ 量具失效（不是代码变好了）: " + t.read(),
                t.hasConsecutiveUsers());
        assertNotNull("第二轮的基线也该缺东西: " + t.read(), err.get());
    }

    // ===== 2. token 身份校验 + 幂等释放 =====

    @Test
    public void releaseIsIdempotentAndIdentityChecked() throws Exception {
        final TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry();
        final TurnLease.Token first = reg.acquire("s1", "k1", Long.valueOf(1L), Long.valueOf(1000L));
        assertNotNull(first);
        assertSame(first, reg.holderOf("s1"));
        assertTrue("本人释放成功", reg.release(first));
        assertNull(reg.holderOf("s1"));
        assertFalse("重复释放是安全空操作", reg.release(first));
        assertTrue(first.isReleased());

        // 换一个线程、另一个 token 拿走同一个 session，然后把「陈旧 token」递进来释放
        final TurnLease.Token second = reg.acquire("s1", "k2", Long.valueOf(2L), Long.valueOf(1000L));
        assertSame(second, reg.holderOf("s1"));
        final AtomicBoolean stale = new AtomicBoolean(true);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                stale.set(reg.release(first));
            }
        });
        t.start();
        t.join(5000);
        assertFalse("陈旧 token 释放不掉", stale.get());
        assertSame("租约仍在第二个人手上", second, reg.holderOf("s1"));
        assertTrue(reg.release(second));
    }

    @Test
    public void crossThreadReleaseCannotLeaveTheLockHeldForever() throws Exception {
        // Java 锁的 owner 是线程：token 身份对得上但线程不对 ⇒ 必须拒放，
        // 否则 holder 被清空而锁仍被原线程持有，这个 session 从此永久楔死。
        final TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry();
        final TurnLease.Token tok = reg.acquire("sx", "k", Long.valueOf(1L), Long.valueOf(1000L));
        final AtomicBoolean byOtherThread = new AtomicBoolean(true);
        Thread other = new Thread(new Runnable() {
            @Override public void run() {
                byOtherThread.set(reg.release(tok));
            }
        });
        other.start();
        other.join(5000);
        assertFalse("跨线程释放必须被拒", byOtherThread.get());
        assertSame("拒绝释放后持有者不变", tok, reg.holderOf("sx"));
        final AtomicBoolean gotInTime = new AtomicBoolean(false);
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() {
                TurnLease.Token t2 = reg.acquire("sx", "late", Long.valueOf(2L), Long.valueOf(200L));
                gotInTime.set(t2 != null && !t2.isDegraded());
                if (gotInTime.get()) {
                    reg.release(t2);
                }
            }
        });
        waiter.start();
        waiter.join(5000);
        assertFalse("持锁线程还没放，别人不该挤进去（这一轮降级才对）", gotInTime.get());
        assertTrue("本线程释放成功", reg.release(tok));
        // 真释放之后，下一轮才拿得到
        TurnLease.Token after = reg.acquire("sx", "k2", Long.valueOf(3L), Long.valueOf(300L));
        assertFalse(after.isDegraded());
        assertTrue(reg.release(after));
    }

    // ===== 3. 超时 fail-open：宁可交织也绝不楔死 =====

    @Test
    public void leaseWaitTimeoutFailsOpenInsteadOfWedging() throws Exception {
        final TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry();
        TurnLease.Token holder = reg.acquire("s3", "busy-chat", Long.valueOf(1L), Long.valueOf(1000L));
        assertNotNull(holder);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<TurnLease.Token> waiterToken = new AtomicReference<TurnLease.Token>();
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    waiterToken.set(reg.acquire("s3", "waiting-chat", Long.valueOf(2L), Long.valueOf(120L)));
                } finally {
                    done.countDown();
                }
            }
        });
        waiter.start();
        assertTrue("等待方必须在超时后继续跑，而不是楔死", done.await(5000L, TimeUnit.MILLISECONDS));
        TurnLease.Token degraded = waiterToken.get();
        assertTrue("等不到 ⇒ 降级放行（fail-open）", degraded.isDegraded());
        assertFalse("降级 token 什么都不持有，释放必须是空操作", reg.release(degraded));
        assertSame("降级放行不会动真持有者的租约", holder, reg.holderOf("s3"));
        assertTrue(reg.release(holder));
    }

    @Test
    public void degradedTurnIsReportedToTheCallerNotSwallowed() throws Exception {
        // bus 侧要能看见 fail-open 发生过（leaseTimeoutCount 是这条的载体），这里先钉注册表语义
        final TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry();
        TurnLease.Token holder = reg.acquire("sd", "busy", Long.valueOf(1L), Long.valueOf(1000L));
        final AtomicInteger degraded = new AtomicInteger();
        List<Thread> waiters = new ArrayList<Thread>();
        for (int i = 0; i < 3; i++) {
            final int n = i;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    TurnLease.Token tok = reg.acquire("sd", "w" + n, Long.valueOf(n), Long.valueOf(60L));
                    if (tok.isDegraded()) {
                        degraded.incrementAndGet();
                    } else {
                        reg.release(tok);
                    }
                }
            });
            waiters.add(t);
            t.start();
        }
        for (Thread t : waiters) {
            t.join(10_000);
        }
        assertEquals("三个等待者都必须在超时后放行", 3, degraded.get());
        assertTrue(reg.release(holder));
    }

    // ===== 4. rebind：一轮中途换 session_id =====

    @Test
    public void rebindAliasesHeldLeaseOntoNewSessionId() throws Exception {
        final TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry();
        final TurnLease.Token tok = reg.acquire("old", "k", Long.valueOf(1L), Long.valueOf(500L));
        assertTrue(reg.rebind(tok, "new"));
        assertEquals("new", tok.sessionId());
        assertSame("旧 key 仍指向同一把租约（等它空闲再清）", tok, reg.holderOf("old"));
        assertSame(tok, reg.holderOf("new"));
        // 另一个路由键按新 id 解析进来 ⇒ 必须与本轮串行（本例：拿不到，超时降级）
        final AtomicBoolean blocked = new AtomicBoolean(false);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                TurnLease.Token second = reg.acquire("new", "k2", Long.valueOf(2L), Long.valueOf(80L));
                blocked.set(second.isDegraded());
            }
        });
        t.start();
        t.join(5000);
        assertTrue("新 id 的下一轮被本轮挡住", blocked.get());
        assertTrue("释放走别名后的 id", reg.release(tok));
        assertNull(reg.holderOf("new"));
    }

    @Test
    public void rebindRefusesToMergeTwoLiveSerializationDomains() throws Exception {
        final TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry();
        final TurnLease.Token a = reg.acquire("sa", "ka", Long.valueOf(1L), Long.valueOf(500L));
        TurnLease.Token b = reg.acquire("sb", "kb", Long.valueOf(2L), Long.valueOf(500L));
        // b 由别的线程持有才算「活的」；这里 b 在本线程（可重入），用 holder 判活即可
        assertFalse("不能中途将本租约并进他人 domain：sb 的租约是活的", reg.rebind(a, "sb"));
        assertEquals("sa", a.sessionId());
        assertTrue(reg.release(b));
        assertTrue("目标空闲后可以并过去", reg.rebind(a, "sb"));
        assertEquals("sb", a.sessionId());
        assertTrue(reg.release(a));
        assertFalse("已释放的 token 不能再 rebind", reg.rebind(a, "sc"));
    }

    // ===== 5. 注册表有界，但绝不逐出活租约 =====

    @Test
    public void evictionNeverDropsALiveLease() {
        TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry(2);
        TurnLease.Token keep = reg.acquire("hot", "k", Long.valueOf(1L), Long.valueOf(500L));
        reg.release(reg.acquire("cold1", "k", Long.valueOf(2L), Long.valueOf(500L)));
        reg.release(reg.acquire("cold2", "k", Long.valueOf(3L), Long.valueOf(500L)));
        TurnLease.Token fresh = reg.acquire("fresh", "k", Long.valueOf(4L), Long.valueOf(500L));
        assertSame("表上限不能把活租约挤掉", keep, reg.holderOf("hot"));
        assertSame(fresh, reg.holderOf("fresh"));
        assertTrue(reg.release(keep));
        assertTrue(reg.release(fresh));
    }

    @Test
    public void registryCountsEveryTrackedSession() {
        TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry(64);
        TurnLease.Token a = reg.acquire("sa", "ka", Long.valueOf(1L), Long.valueOf(100L));
        TurnLease.Token b = reg.acquire("sb", "kb", Long.valueOf(2L), Long.valueOf(100L));
        assertEquals(2, reg.size());
        reg.release(a);
        assertEquals("空闲条目也计数（与 hermes __len__ 同口径）", 2, reg.size());
        reg.release(b);
    }

    @Test
    public void noSessionIdMeansNoLease() {
        TurnLease.SessionTurnLeaseRegistry reg = new TurnLease.SessionTurnLeaseRegistry();
        assertNull("解析不出 session_id ⇒ 没有 transcript 归属，也就无锁可上",
                reg.acquire(null, "k", Long.valueOf(1L), Long.valueOf(100L)));
        assertNull(reg.acquire("", "k", Long.valueOf(1L), Long.valueOf(100L)));
        assertEquals(0, reg.size());
    }
}
