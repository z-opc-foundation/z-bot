package com.zifang.z.bot.delegate;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P27 靶子二：委托现场台账 —— <b>进程死了还在盘上</b>，回收要有上限且正反两问都有证据。
 */
public class DelegationLedgerTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File root;
    private DelegationLedger ledger;

    @Before
    public void setUp() {
        root = new File(tmp.getRoot(), "live");
        ledger = new DelegationLedger(root);
    }

    // ===== 建账即落盘 =====

    @Test
    public void createWritesTheSceneBeforeAnythingRuns() {
        assertEquals("建账之前目录里什么都没有", 0, countDirs(root));
        DelegationLedger.Entry e = ledger.create("dlg-1", "查一下 X 的用法", 0, "probe", null);
        File dir = new File(root, "dlg-1");
        assertTrue("建账必须当场建目录: " + dir, dir.isDirectory());
        File state = new File(dir, "state.json");
        assertTrue("建账必须当场写 state.json", state.isFile() && state.length() > 0);
        assertTrue("建账必须留下可读的事件流", new File(dir, "events.log").isFile());
        assertEquals(DelegateState.QUEUED, e.state);
        assertEquals(DeliveryState.PENDING, e.delivery);
        assertEquals(0, e.deliveryAttempts);
        assertTrue("owner_pid 必须落盘，否则无从判孤儿", e.ownerPid > 0);
        assertEquals(1, ledger.saveCount());
    }

    /** 换一个新台账实例读同一个根 = "进程重启之后读得回来"。 */
    @Test
    public void sceneIsReadableFromAFreshInstance() {
        DelegationLedger.Entry e = ledger.create("dlg-2", "task", 1, "lbl", new File(tmp.getRoot(), "child"));
        ledger.advance(e, DelegateEvent.TASK_SPAWNED, "起跑");
        e.reply = "子代理的回复正文";
        ledger.advance(e, DelegateEvent.TASK_COMPLETED, "收工");

        DelegationLedger reopened = new DelegationLedger(root);
        DelegationLedger.Entry back = reopened.load("dlg-2");
        assertNotNull("重启后读不回现场，台账就白写", back);
        assertEquals(DelegateState.DONE, back.state);
        assertEquals("子代理的回复正文", back.reply);
        assertEquals(e.dispatchedAt, back.dispatchedAt);
        assertTrue(back.startedAt > 0);
        assertTrue(back.finishedAt > 0);
        assertTrue(back.childSession.endsWith("child"));
        assertEquals("lbl", back.label);
        assertEquals(1, back.depth);
    }

    @Test
    public void eventsLogCarriesWireNamesInOrder() {
        DelegationLedger.Entry e = ledger.create("dlg-3", "task", 0, "", null);
        ledger.advance(e, DelegateEvent.TASK_SPAWNED, "go");
        ledger.advance(e, DelegateEvent.TASK_FAILED, "boom");
        List<String> lines = ledger.tail("dlg-3", 50);
        String joined = String.join("\n", lines);
        assertTrue(joined, joined.indexOf("delegate.dispatch") >= 0);
        assertTrue(joined, joined.indexOf("delegate.task_spawned") >= 0);
        assertTrue(joined, joined.indexOf("delegate.task_failed") >= 0);
        assertTrue("事件流必须按序：\n" + joined,
                joined.indexOf("delegate.dispatch") < joined.indexOf("delegate.task_spawned")
                        && joined.indexOf("delegate.task_spawned") < joined.indexOf("delegate.task_failed"));
        int eventLines = 0;
        for (String line : lines) {
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) {
                continue; // header 段（hermes 的 "pre-created with a header" 同位）
            }
            eventLines++;
            assertTrue(line, line.matches("^\\d+\\|delegate\\.[a-z_]+\\|.*$"));
        }
        assertEquals("三条事件三行，header 不许混进格式判定", 3, eventLines);
    }

    @Test
    public void atomicWriteLeavesNoTempFileAndNoHalfState() {
        DelegationLedger.Entry e = ledger.create("dlg-4", "task", 0, "", null);
        for (int i = 0; i < 3; i++) {
            ledger.advance(e, DelegateEvent.TASK_PROGRESS, "心跳 " + i);
        }
        assertFalse("tmp 文件不许留在盘上", new File(root, "dlg-4/state.json.tmp").exists());
        assertTrue(new File(root, "dlg-4/state.json").isFile());
        assertEquals(DelegateState.QUEUED, ledger.load("dlg-4").state);
    }

    // ===== 密钥遮罩 =====

    @Test
    public void secretsAreMaskedBeforeHittingDisk() {
        DelegationLedger.Entry e = ledger.create("dlg-5",
                "读一下配置，key=sk-abcdefghij0123456789 顺便找 minimax.api-key=zzz-111222333444555", 0, "", null);
        ledger.advance(e, DelegateEvent.TASK_SPAWNED, "起跑");
        e.reply = "答复里回显了 api_key: SUPERSECRETVVALUE123456";
        ledger.advance(e, DelegateEvent.TASK_COMPLETED, "token=sk-abcdefghij0123456789");
        String raw = readFile(new File(root, "dlg-5/state.json"));
        String events = readFile(new File(root, "dlg-5/events.log"));
        assertFalse("密钥不许原文落盘:\n" + raw, raw.contains("abcdefghij0123456789"));
        assertFalse(raw, raw.contains("SUPERSECRETVVALUE123456"));
        assertFalse(events, events.contains("abcdefghij0123456789"));
        assertTrue(raw, raw.contains("REDACTED"));
    }

    // ===== 回收：正反两问 =====

    @Test
    public void pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse() {
        long now = System.currentTimeMillis();
        // ① 超期的终态现场 ⇒ 删
        DelegationLedger.Entry old = ledger.create("dlg-expired", "t", 0, "", null);
        ledger.advance(old, DelegateEvent.TASK_SPAWNED, "");
        ledger.advance(old, DelegateEvent.TASK_COMPLETED, "");
        old.finishedAt = now - (DelegationLedger.LIVE_RETENTION_DAYS + 1) * DAY;
        old.updatedAt = old.finishedAt;
        ledger.save(old);
        // ② 没超期的终态现场 ⇒ 留（正向证据：未过期不许删）
        DelegationLedger.Entry fresh = ledger.create("dlg-fresh", "t", 0, "", null);
        ledger.advance(fresh, DelegateEvent.TASK_SPAWNED, "");
        ledger.advance(fresh, DelegateEvent.TASK_COMPLETED, "");
        fresh.finishedAt = now - DAY;
        fresh.updatedAt = fresh.finishedAt;
        ledger.save(fresh);
        // ③ 超期但非终态（真在飞的）⇒ 一律不删
        DelegationLedger.Entry running = ledger.create("dlg-running", "t", 0, "", null);
        ledger.advance(running, DelegateEvent.TASK_SPAWNED, "");
        running.updatedAt = now - (DelegationLedger.LIVE_RETENTION_DAYS + 30) * DAY;
        running.startedAt = running.updatedAt;
        ledger.save(running);
        // 把三条现场的目录 mtime 全敲老：证明回收判的是盘上字段，不是 mtime
        touch(new File(root, "dlg-fresh"));

        int removed = ledger.pruneStale(DelegationLedger.LIVE_RETENTION_MILLIS, now);
        assertEquals("只该删掉那一条超期终态: " + ledger.root(), 1, removed);
        assertFalse(new File(root, "dlg-expired").exists());
        assertTrue("未过期的终态现场被误删", new File(root, "dlg-fresh").isDirectory());
        assertTrue("在飞现场被误删", new File(root, "dlg-running").isDirectory());
        assertEquals(DelegateState.RUNNING, ledger.load("dlg-running").state);
    }

    @Test
    public void pruneHonoursTheRetentionWindowBoundary() {
        long now = System.currentTimeMillis();
        long window = DelegationLedger.LIVE_RETENTION_MILLIS;
        // i=0 差一分钟到点 ⇒ 留；i=1/2 已过点 ⇒ 删
        for (int i = 0; i < 3; i++) {
            DelegationLedger.Entry e = ledger.create("dlg-edge-" + i, "t", 0, "", null);
            ledger.advance(e, DelegateEvent.TASK_SPAWNED, "");
            ledger.advance(e, DelegateEvent.TASK_FAILED, "");
            long age = window + (i * 2 - 1) * 60_000L;
            e.finishedAt = now - age;
            e.updatedAt = e.finishedAt;
            ledger.save(e);
        }
        assertEquals("到点之后才删：三条里两条已过期", 2,
                ledger.pruneStale(window, now));
        assertEquals("第二次跑一条都不该剩着可删", 0,
                ledger.pruneStale(window, now));
        assertEquals(1, countDirs(root));
        assertTrue(new File(root, "dlg-edge-0").isDirectory());
    }

    @Test
    public void retentionConstantMatchesHermesSevenDays() {
        assertEquals(7, DelegationLedger.LIVE_RETENTION_DAYS);
        assertEquals(7L * 24 * 60 * 60 * 1000, DelegationLedger.LIVE_RETENTION_MILLIS);
    }

    @Test
    public void pruneReturnsZeroOnEmptyOrDisabledLedger() {
        assertEquals(0, ledger.pruneStale(0L, System.currentTimeMillis()));
        assertEquals(0, new DelegationLedger(null).pruneStale(0L, System.currentTimeMillis()));
    }

    // ===== 孤儿认领：正反两问 =====

    @Test
    public void orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes() {
        long now = System.currentTimeMillis();
        DelegationLedger.Entry dead = ledger.create("dlg-orphan", "跑到一半死了", 0, "", null);
        ledger.advance(dead, DelegateEvent.TASK_SPAWNED, "");
        dead.updatedAt = now - DelegationLedger.ORPHAN_STALE_MILLIS - 1000L;
        ledger.save(dead);

        DelegationLedger.Entry alive = ledger.create("dlg-alive", "真的在跑", 0, "", null);
        ledger.advance(alive, DelegateEvent.TASK_SPAWNED, "");
        alive.updatedAt = now - 1000L;
        ledger.save(alive);

        DelegationLedger.Entry done = ledger.create("dlg-done", "早就收工", 0, "", null);
        ledger.advance(done, DelegateEvent.TASK_SPAWNED, "");
        ledger.advance(done, DelegateEvent.TASK_COMPLETED, "");
        done.updatedAt = now - 10 * DelegationLedger.ORPHAN_STALE_MILLIS;
        ledger.save(done);

        int adopted = ledger.adoptOrphans(DelegationLedger.ORPHAN_STALE_MILLIS, now);
        assertEquals("只该认领那一条超期的非终态现场", 1, adopted);
        assertEquals(DelegateState.UNKNOWN, ledger.load("dlg-orphan").state);
        assertEquals("在飞的现场不许被改判", DelegateState.RUNNING, ledger.load("dlg-alive").state);
        assertEquals("终态现场不该被孤儿流程再过一遍", DelegateState.DONE, ledger.load("dlg-done").state);
        assertTrue("孤儿必须留下判词事件",
                String.join("\n", ledger.tail("dlg-orphan", 50)).contains("delegate.orphan_adopted"));
    }

    @Test
    public void unknownScenesAreTerminalAndThusPrunable() {
        long now = System.currentTimeMillis();
        DelegationLedger.Entry e = ledger.create("dlg-orph2", "t", 0, "", null);
        ledger.advance(e, DelegateEvent.TASK_SPAWNED, "");
        ledger.advance(e, DelegateEvent.ORPHAN_ADOPTED, "死了");
        e.finishedAt = now - 2 * DAY;
        ledger.save(e);
        assertTrue(e.state.terminal());
        assertEquals(1, ledger.pruneStale(DAY, now));
        assertFalse(new File(root, "dlg-orph2").exists());
    }

    // ===== 降级 / 入参 =====

    @Test
    public void writeFailureDegradesToNoOpInsteadOfBreakingTheAgent() throws Exception {
        File blocked = new File(tmp.getRoot(), "not-a-dir");
        assertTrue(blocked.createNewFile());
        DelegationLedger broken = new DelegationLedger(new File(blocked, "live"));
        DelegationLedger.Entry e = broken.create("dlg-x", "t", 0, "", null);
        assertNotNull("台账坏了也要把记录对象还给调用方", e);
        assertFalse("写失败必须翻转健康旗", broken.healthy());
        assertTrue(broken.lastError(), broken.lastError().contains("create"));
        assertFalse(broken.save(e));
        assertFalse(broken.appendEvent("dlg-x", DelegateEvent.TASK_PROGRESS, "x"));
        assertNull(broken.load("dlg-x"));
        assertEquals(0, broken.list().size());
    }

    @Test
    public void disabledLedgerIsHarmless() {
        DelegationLedger off = new DelegationLedger(null);
        assertFalse(off.enabled());
        DelegationLedger.Entry e = off.create("dlg-y", "t", 0, "", null);
        assertFalse(off.save(e));
        assertFalse(off.appendEvent("dlg-y", DelegateEvent.TASK_PROGRESS, "x"));
        assertNull(off.load("dlg-y"));
        assertEquals(0, off.list().size());
        assertEquals(0, off.tail("dlg-y", 5).size());
        assertEquals(0, off.pruneStale(0L, System.currentTimeMillis()));
        assertEquals(0, off.adoptOrphans(0L, System.currentTimeMillis()));
    }

    @Test
    public void pathEscapingIdsAreRejected() {
        assertIllegal("dlg/../evil");
        assertIllegal("../evil");
        assertIllegal("a/b");
        assertIllegal("");
        assertIllegal(null);
        assertIllegal("evil;rm -rf");
    }

    @Test
    public void corruptStateFileIsIgnoredNotCrashed() throws Exception {
        File d = new File(root, "dlg-corrupt");
        assertTrue(d.mkdirs());
        writeFile(new File(d, "state.json"), "{ this is not json ");
        assertNull(ledger.load("dlg-corrupt"));
        assertEquals("坏文件不该进 list()", 0, ledger.list().size());
        // 但回收不能因此被卡住：坏目录留着，正常流程继续
        DelegationLedger.Entry ok = ledger.create("dlg-good", "t", 0, "", null);
        ledger.advance(ok, DelegateEvent.TASK_SPAWNED, "");
        ledger.advance(ok, DelegateEvent.TASK_STOPPED, "");
        ok.finishedAt = System.currentTimeMillis() - 10 * DAY;
        ledger.save(ok);
        assertEquals(1, ledger.pruneStale(DAY, System.currentTimeMillis()));
        assertTrue(d.isDirectory());
    }

    @Test
    public void advanceRejectsIllegalTransitionBeforeTouchingDisk() {
        DelegationLedger.Entry e = ledger.create("dlg-illegal", "t", 0, "", null);
        ledger.advance(e, DelegateEvent.TASK_SPAWNED, "");
        ledger.advance(e, DelegateEvent.TASK_COMPLETED, "");
        long savedBefore = ledger.saveCount();
        try {
            ledger.advance(e, DelegateEvent.TASK_SPAWNED, "收工之后不能再起跑");
            throw new AssertionError("终态后必须抛");
        } catch (DelegateTransitions.IllegalTransitionException expected) {
            // ok
        }
        assertEquals("抛之前不许先把半截状态写进盘", savedBefore, ledger.saveCount());
        assertEquals(DelegateState.DONE, ledger.load("dlg-illegal").state);
    }

    @Test
    public void listIsOrderedByDispatchTime() {
        for (String id : new String[]{"dlg-b", "dlg-a", "dlg-c"}) {
            ledger.create(id, "t", 0, "", null);
        }
        List<DelegationLedger.Entry> all = ledger.list();
        assertEquals(3, all.size());
        for (DelegationLedger.Entry e : all) {
            assertTrue(e.id, e.id.startsWith("dlg-"));
        }
    }

    // ===== helpers =====

    private static void assertIllegal(String id) {
        try {
            new DelegationLedger(new File("/definitely-not-here")).dirOf(id);
            throw new AssertionError("非法 id 必须抛: " + id);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static int countDirs(File root) {
        File[] kids = root == null ? null : root.listFiles();
        int n = 0;
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 把目录 mtime 敲老：如果回收判的是 mtime，这条就会把它误删（用来证伪 mtime 口径）。 */
    private static void touch(File f) {
        long old = System.currentTimeMillis() - 400L * DAY;
        assertTrue("setLastModified 没生效，这条证伪手段失效: " + f, f.setLastModified(old));
        assertEquals(old, f.lastModified());
    }

    private static String readFile(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeFile(File f, String content) throws Exception {
        try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8")) {
            w.write(content);
        }
    }
}
