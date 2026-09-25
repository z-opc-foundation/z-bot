package com.zifang.z.bot.store;

import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 写路径（P15 第 5 项）：{@code BEGIN IMMEDIATE} + 20–150ms 抖动重试 + 每 N 次写 checkpoint，
 * 以及三张新表的真实读写（否则就是 0 消费者的抽象）。
 *
 * <p>并发压力用两个 {@link StateStore} 实例（两条独立连接，同一文件）在一个 JVM 里对打；
 * 跨进程那一档在验收 ③ 用双 JVM 实测，不靠这里代替。</p>
 */
public class StateStoreWritePathTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dbFile() throws Exception {
        return new File(tmp.newFolder("wp" + System.nanoTime()), "state.db");
    }

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text, MessageType.TEXT, null,
                new ArrayList<ToolCall>(), new HashMap<String, Object>());
    }

    private static int countRows(File f, String sql) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        } catch (Exception e) {
            throw new AssertionError(sql + " 失败: " + e, e);
        }
    }

    // ===== appendMessage：P14 的落点 =====

    @Test
    public void appendMessageWritesSequentialIdxAndBumpsCounters() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("s1", "t", null, null, 0, 0, 0);
        assertEquals(0, store.appendMessage("s1", user("第一条")));
        assertEquals(1, store.appendMessage("s1", user("第二条")));
        assertEquals(2, store.appendMessage("s1", user("第三条")));
        assertEquals(3, store.messageCount("s1"));
        assertEquals("sessions.message_count 跟着走", 3,
                store.listSessions().get(0).messageCount);
        assertEquals(3, countRows(f, "SELECT COUNT(*) FROM messages"));
        assertEquals("追加不是整表替换：idx 连续无洞", "0,1,2", joinIdx(f, "s1"));
        assertTrue(store.writeCounters().writes.get() >= 3);
    }

    private static String joinIdx(File f, String sessionId) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT idx FROM messages WHERE session_id='" + sessionId
                     + "' ORDER BY idx")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(rs.getInt(1));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void appendIsVisibleToFtsSearchImmediately() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("s1", "t", null, null, 0, 0, 0);
        store.appendMessage("s1", user("NEEDLE_IN_APPEND"));
        List<StateStore.SearchHit> hits = store.search("NEEDLE_IN_APPEND", 5);
        assertFalse("追加的行要能立刻被全文检索命中", hits.isEmpty());
        store.deleteSession("s1");
        assertTrue("删会话后 FTS 不留幽灵行", store.search("NEEDLE_IN_APPEND", 5).isEmpty());
    }

    // ===== 同 JVM 双连接对打：零丢失，且 SQLITE_BUSY 不冒到调用方 =====

    @Test
    public void concurrentAppendKeepsEveryRowAndNoCallerVisibleBusy() throws Exception {
        File f = dbFile();
        StateStore warm = new StateStore(f);
        warm.upsertSession("storm", "并发", null, null, 0, 0, 0);

        final int writers = 3;
        final int perWriter = 20;
        final StateStore[] stores = new StateStore[writers];
        for (int i = 0; i < writers; i++) {
            stores[i] = new StateStore(f);
        }
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<Integer>> futures = new ArrayList<Future<Integer>>();
        for (int w = 0; w < writers; w++) {
            final StateStore store = stores[w];
            final int tag = w;
            futures.add(pool.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    int ok = 0;
                    for (int i = 0; i < perWriter; i++) {
                        if (store.appendMessage("storm", user("w" + tag + "-" + i)) >= 0) {
                            ok++;
                        }
                    }
                    return Integer.valueOf(ok);
                }
            }));
        }
        pool.shutdown();
        assertTrue("并发写线程没收口", pool.awaitTermination(120, TimeUnit.SECONDS));

        int appended = 0;
        for (Future<Integer> ft : futures) {
            appended += ft.get().intValue();
        }
        int rows = countRows(f, "SELECT COUNT(*) FROM messages WHERE session_id='storm'");
        assertEquals("一条都不能丢", writers * perWriter, rows);
        assertEquals("返回值也得全部成功", writers * perWriter, appended);
        assertEquals("idx 不许撞车（UNIQUE 生效后整段事务重放）",
                rows, countRows(f, "SELECT COUNT(DISTINCT idx) FROM messages WHERE session_id='storm'"));
        for (StateStore store : stores) {
            assertEquals("SQLITE_BUSY 不该冒到调用方: " + store.lastError(),
                    0, store.writeCounters().givenUp.get());
            assertNull(store.lastError());
        }
        // 每个会话只有一个 message_count 真值，且等于行数（并发下没写超也没写少）
        assertEquals(writers * perWriter, new StateStore(f).messageCount("storm"));
    }

    // ===== 计数器 / checkpoint =====

    @Test
    public void countersAndCheckpointAreObservable() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f, StateStore.Options.defaults().checkpointEveryNWrites(3));
        store.upsertSession("s", "t", null, null, 0, 0, 0);
        for (int i = 0; i < 6; i++) {
            store.appendMessage("s", user("m" + i));
        }
        SqliteTx.Counters c = store.writeCounters();
        assertTrue("写了就该计数: " + c, c.writes.get() >= 8);
        assertEquals("一次写都不该放弃: " + c, 0, c.givenUp.get());
        assertTrue("每 3 次提交 checkpoint 一次: " + c, c.checkpoints.get() >= 2);
        assertTrue(store.checkpointNow());
        assertTrue("手工 checkpoint 也要计数: " + c, c.checkpoints.get() >= 3);

        // 默认（50）时小流量不该触发 checkpoint
        StateStore plain = new StateStore(dbFile());
        plain.upsertSession("s", "t", null, null, 0, 0, 0);
        plain.appendMessage("s", user("m"));
        assertEquals(0, plain.writeCounters().checkpoints.get());
    }

    @Test
    public void optionsExposeDefaultsAndPendingConfigKeys() {
        StateStore.Options o = StateStore.Options.defaults();
        assertTrue("BEGIN IMMEDIATE 默认开", o.beginImmediate());
        assertEquals(15, o.writeRetries());
        assertEquals(20L, o.retryMinMillis());
        assertEquals(150L, o.retryMaxMillis());
        assertEquals(1000, o.busyTimeoutMillis());
        assertEquals(50, o.checkpointEveryNWrites());
        assertFalse("auto_prune 默认关", o.autoPrune());
        assertEquals(90, o.retentionDays());
        List<String> keys = StateStore.Options.configKeys();
        assertEquals("等 BotConfig 落这些键: " + keys, 10, keys.size());
        assertTrue(keys.contains("agent.state.prune.auto"));
        assertTrue(keys.contains("agent.state.db.write.begin.immediate"));
        // 覆写生效（gate ④ 的 A/B 开关）
        StateStore.Options off = StateStore.Options.defaults().beginImmediate(false)
                .writeRetries(0).busyTimeoutMillis(0);
        assertFalse(off.beginImmediate());
        assertEquals(0, off.writeRetries());
        assertEquals(0, off.busyTimeoutMillis());
    }

    // ===== 新表的真实消费者 =====

    @Test
    public void stateMetaRoundTrip() throws Exception {
        StateStore store = new StateStore(dbFile());
        assertNull(store.getMeta("k"));
        store.setMeta("k", "v1");
        assertEquals("v1", store.getMeta("k"));
        store.setMeta("k", "v2");
        assertEquals("v2", store.getMeta("k"));
        assertTrue(store.removeMeta("k"));
        assertNull(store.getMeta("k"));
        assertFalse(store.removeMeta("k"));
        // 迁移台账写在这里（本期已有真实写者）
        assertNotNull(store.getMeta(StateStore.META_LAST_MIGRATION));
    }

    @Test
    public void compressionLockIsMutuallyExclusiveAndExpires() throws Exception {
        StateStore store = new StateStore(dbFile());
        store.upsertSession("s1", "t", null, null, 0, 0, 0);
        assertTrue(store.tryAcquireCompressionLock("s1", "agent-A", 60000L));
        assertEquals("agent-A", store.compressionLockHolder("s1"));
        assertFalse("别人在飞就不能抢", store.tryAcquireCompressionLock("s1", "agent-B", 60000L));
        assertEquals("agent-A", store.compressionLockHolder("s1"));
        assertFalse("只有持锁人能释放", store.releaseCompressionLock("s1", "agent-B"));
        assertTrue(store.releaseCompressionLock("s1", "agent-A"));
        assertNull(store.compressionLockHolder("s1"));
        assertTrue(store.tryAcquireCompressionLock("s1", "agent-B", 60000L));
        assertEquals(0, store.clearExpiredCompressionLocks());
        assertTrue(store.releaseCompressionLock("s1", "agent-B"));
    }

    @Test
    public void expiredCompressionLockIsClearedAndReclaimable() throws Exception {
        StateStore store = new StateStore(dbFile());
        store.upsertSession("s1", "t", null, null, 0, 0, 0);
        // ttl 下限 1000ms（防手滑填 0 导致锁瞬间过期）
        assertTrue(store.tryAcquireCompressionLock("s1", "crashed-agent", 1L));
        assertNotNull(store.compressionLockHolder("s1"));
        Thread.sleep(1200L);
        assertNull("过期后不该还显示有人持锁", store.compressionLockHolder("s1"));
        assertEquals(1, store.clearExpiredCompressionLocks());
        assertTrue("过期锁可被接管", store.tryAcquireCompressionLock("s1", "new-agent", 60000L));
        assertEquals("new-agent", store.compressionLockHolder("s1"));
    }

    @Test
    public void gatewayRoutingCrudWithScopeIsolation() throws Exception {
        StateStore store = new StateStore(dbFile());
        store.putGatewayRoute("profile:a", "chat-1", "{\"peer\":\"u1\"}");
        store.putGatewayRoute("", "chat-1", "{\"peer\":\"default-scope\"}");
        store.putGatewayRoute("profile:b", "chat-2", "{\"peer\":\"u2\"}");

        assertEquals("{\"peer\":\"u1\"}", store.getGatewayRoute("profile:a", "chat-1"));
        assertEquals("{\"peer\":\"default-scope\"}", store.getGatewayRoute(null, "chat-1"));
        assertNull("scope 之间互相看不见", store.getGatewayRoute("profile:b", "chat-1"));

        store.putGatewayRoute("profile:a", "chat-1", "{\"peer\":\"u1-updated\"}");
        assertEquals(1, store.listGatewayRoutes("profile:a").size());
        assertEquals("{\"peer\":\"u1-updated\"}", store.getGatewayRoute("profile:a", "chat-1"));
        assertEquals(1, store.listGatewayRoutes("profile:b").size());
        assertTrue(store.listGatewayRoutes("profile:a").get(0).updatedAtMillis > 0L);

        assertTrue(store.deleteGatewayRoute("profile:a", "chat-1"));
        assertFalse(store.deleteGatewayRoute("profile:a", "chat-1"));
        assertNull(store.getGatewayRoute("profile:a", "chat-1"));
        // null scope == 默认作用域 ""，不是"列出所有"
        assertEquals(1, store.listGatewayRoutes(null).size());
        assertEquals(2, store.listGatewayRoutes("").size() + store.listGatewayRoutes("profile:b").size());
    }

    // ===== 失败语义：默认吞并记账，可配置上抛 =====

    @Test
    public void writeFailureIsRecordedByDefaultAndThrownWhenConfigured() throws Exception {
        File f = dbFile();
        StateStore quiet = new StateStore(f);
        dropSessionsTable(f); // 让写必炸（非撞锁）
        quiet.upsertSession("x", "t", null, null, 0, 0, 0);
        assertNotNull("失败必须留下原始错误", quiet.lastError());
        assertTrue("该记为放弃: " + quiet.writeCounters(), quiet.writeCounters().givenUp.get() >= 1);

        StateStore.Options loud = StateStore.Options.defaults().propagateWriteFailures(true);
        File f2 = dbFile();
        StateStore throwing = new StateStore(f2, loud);
        dropSessionsTable(f2);
        try {
            throwing.upsertSession("x", "t", null, null, 0, 0, 0);
            fail("propagateWriteFailures(true) 时写失败要上抛");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("state.db 写失败"));
        }
    }

    private static void dropSessionsTable(File f) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("DROP TABLE sessions");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void retryLadderCarriesAWriteThroughAnExternalLockHolder() throws Exception {
        File f = dbFile();
        // busy_timeout=0 ⇒ 撞锁立刻 SQLITE_BUSY ⇒ 只能靠抖动重试熬过去（重试阶梯的确定性用例）
        final StateStore store = new StateStore(f,
                StateStore.Options.defaults().busyTimeoutMillis(0));
        store.upsertSession("s", "t", null, null, 0, 0, 0);

        Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        Statement bs = blocker.createStatement();
        bs.execute("PRAGMA busy_timeout=0");
        bs.execute("BEGIN IMMEDIATE");
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> done = single.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    return store.appendMessage("s", user("撞锁期间追加"));
                }
            });
            Thread.sleep(200L);
            bs.execute("COMMIT");
            blocker.close();
            Integer result = done.get(30, TimeUnit.SECONDS);
            assertTrue("撞锁期间靠重试熬过去: " + store.writeCounters(),
                    result != null && result.intValue() >= 0);
            assertTrue("该有撞锁/重试读数: " + store.writeCounters(),
                    store.writeCounters().busyCollisions.get() >= 1);
            assertTrue("重试次数 >= 1: " + store.writeCounters(),
                    store.writeCounters().retries.get() >= 1);
            assertEquals("一次都没放弃: " + store.writeCounters(),
                    0, store.writeCounters().givenUp.get());
        } finally {
            single.shutdownNow();
            try {
                bs.execute("ROLLBACK");
            } catch (Exception ignored) {
            }
            try {
                bs.close();
            } catch (Exception ignored) {
            }
            SqliteTx.close(blocker);
        }
        assertEquals("撞锁期间只追加了那一条", 1,
                countRows(f, "SELECT COUNT(*) FROM messages"));
        assertEquals("撞锁期间只追加了那一条", 1, store.messageCount("s"));
        assertEquals("撞锁期间只追加了那一条", 1,
                countRows(f, "SELECT COUNT(*) FROM messages WHERE content='撞锁期间追加'"));
        assertNull(store.lastError());
    }

    @Test
    public void noRetryModeIsAvailableForMeasurement() throws Exception {
        // gate ③/④ 的"改前"读数开关：只允许在测量时用；默认路径绝不能这么配
        File f = dbFile();
        StateStore measured = new StateStore(f, StateStore.Options.defaults()
                .beginImmediate(false).writeRetries(0).busyTimeoutMillis(0));
        measured.upsertSession("s", "t", null, null, 0, 0, 0);
        assertEquals(0, measured.appendMessage("s", user("无重试模式下的追加")));
        assertEquals(0, measured.writeCounters().retries.get());
        assertFalse(measured.options().beginImmediate());
        assertEquals(0, measured.options().writeRetries());
        assertEquals(0, measured.options().busyTimeoutMillis());
    }
}
