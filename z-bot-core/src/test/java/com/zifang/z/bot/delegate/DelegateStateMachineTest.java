package com.zifang.z.bot.delegate;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Collections;

/**
 * P27 靶子三：委托面状态机的<b>穷举</b>复算用例。
 *
 * <p>期望表在本文件里独立手写（不从 {@link DelegateTransitions} 反推），
 * 然后对 (状态 × 事件) 全笛卡尔积逐格比对：表里没写到的格子一律算"必须抛"。
 * 于是两种偷工减料都会当场变红：
 * ① 生产表多开了一条口子（实得≠期望）；
 * ② 新加了一个事件却没在任一轴登记（{@link #everyEventHasADocumentedVerdict}）。</p>
 */
public class DelegateStateMachineTest {

    // ===== 期望表（手写基准） =====

    private static Map<DelegateState, Map<DelegateEvent, DelegateState>> expectedLifecycle() {
        Map<DelegateState, Map<DelegateEvent, DelegateState>> t =
                new EnumMap<DelegateState, Map<DelegateEvent, DelegateState>>(DelegateState.class);
        for (DelegateState s : DelegateState.values()) {
            t.put(s, new EnumMap<DelegateEvent, DelegateState>(DelegateEvent.class));
        }
        Map<DelegateEvent, DelegateState> q = t.get(DelegateState.QUEUED);
        q.put(DelegateEvent.TASK_SPAWNED, DelegateState.RUNNING);
        q.put(DelegateEvent.TASK_PROGRESS, DelegateState.QUEUED);
        q.put(DelegateEvent.BUDGET_UNCLAMPED, DelegateState.QUEUED);
        q.put(DelegateEvent.TASK_FAILED, DelegateState.FAILED);
        q.put(DelegateEvent.TASK_STOPPED, DelegateState.STOPPED);
        q.put(DelegateEvent.ORPHAN_ADOPTED, DelegateState.UNKNOWN);
        Map<DelegateEvent, DelegateState> r = t.get(DelegateState.RUNNING);
        r.put(DelegateEvent.TASK_COMPLETED, DelegateState.DONE);
        r.put(DelegateEvent.TASK_PROGRESS, DelegateState.RUNNING);
        r.put(DelegateEvent.BUDGET_UNCLAMPED, DelegateState.RUNNING);
        r.put(DelegateEvent.TASK_FAILED, DelegateState.FAILED);
        r.put(DelegateEvent.TASK_STOPPED, DelegateState.STOPPED);
        r.put(DelegateEvent.ORPHAN_ADOPTED, DelegateState.UNKNOWN);
        // DONE / FAILED / STOPPED / UNKNOWN 四个终态：一行出口都不给。
        return t;
    }

    private static Map<DeliveryState, Map<DelegateEvent, DeliveryState>> expectedDelivery() {
        Map<DeliveryState, Map<DelegateEvent, DeliveryState>> t =
                new EnumMap<DeliveryState, Map<DelegateEvent, DeliveryState>>(DeliveryState.class);
        for (DeliveryState s : DeliveryState.values()) {
            t.put(s, new EnumMap<DelegateEvent, DeliveryState>(DelegateEvent.class));
        }
        Map<DelegateEvent, DeliveryState> p = t.get(DeliveryState.PENDING);
        p.put(DelegateEvent.RESULT_CLAIMED, DeliveryState.CLAIMED);
        p.put(DelegateEvent.RESULT_DROPPED, DeliveryState.DROPPED);
        Map<DelegateEvent, DeliveryState> c = t.get(DeliveryState.CLAIMED);
        c.put(DelegateEvent.RESULT_DELIVERED, DeliveryState.DELIVERED);
        c.put(DelegateEvent.RESULT_DROPPED, DeliveryState.DROPPED);
        c.put(DelegateEvent.RESULT_CLAIMED, DeliveryState.CLAIMED);
        c.put(DelegateEvent.RESULT_RETRY, DeliveryState.PENDING);
        // DELIVERED / DROPPED 是投递终态：一行出口都不给。
        return t;
    }

    // ===== 穷举复算 =====

    @Test
    public void lifecycleMatrixIsExhaustive() {
        Map<DelegateState, Map<DelegateEvent, DelegateState>> want = expectedLifecycle();
        int checked = 0;
        List<String> mismatched = new ArrayList<String>();
        for (DelegateState from : DelegateState.values()) {
            for (DelegateEvent e : DelegateEvent.values()) {
                DelegateState target = want.get(from).get(e);
                checked++;
                if (target == null) {
                    try {
                        DelegateState got = DelegateTransitions.next(from, e);
                        mismatched.add(from + " --" + e.wireName() + "--> " + got + "（期望抛，实得不抛）");
                    } catch (DelegateTransitions.IllegalTransitionException expected) {
                        // 大声失败，符合期望
                    }
                } else {
                    try {
                        DelegateState got = DelegateTransitions.next(from, e);
                        if (got != target) {
                            mismatched.add(from + " --" + e.wireName() + "--> 期望 " + target + " 实得 " + got);
                        }
                    } catch (DelegateTransitions.IllegalTransitionException boom) {
                        mismatched.add(from + " --" + e.wireName() + "--> 期望 " + target + " 实得抛 " + boom.getMessage());
                    }
                }
            }
        }
        assertEquals("复算格子数 = 状态 × 事件", DelegateState.values().length * DelegateEvent.values().length, checked);
        assertEquals("生命周期迁移表与手写基准不一致:\n" + join(mismatched), Collections.<String>emptyList(), mismatched);
    }

    @Test
    public void deliveryMatrixIsExhaustive() {
        Map<DeliveryState, Map<DelegateEvent, DeliveryState>> want = expectedDelivery();
        int checked = 0;
        List<String> mismatched = new ArrayList<String>();
        for (DeliveryState from : DeliveryState.values()) {
            for (DelegateEvent e : DelegateEvent.values()) {
                DeliveryState target = want.get(from).get(e);
                checked++;
                if (target == null) {
                    try {
                        DeliveryState got = DelegateTransitions.nextDelivery(from, e);
                        mismatched.add(from + " --" + e.wireName() + "--> " + got + "（期望抛，实得不抛）");
                    } catch (DelegateTransitions.IllegalTransitionException expected) {
                        // ok
                    }
                } else {
                    try {
                        DeliveryState got = DelegateTransitions.nextDelivery(from, e);
                        if (got != target) {
                            mismatched.add(from + " --" + e.wireName() + "--> 期望 " + target + " 实得 " + got);
                        }
                    } catch (DelegateTransitions.IllegalTransitionException boom) {
                        mismatched.add(from + " --" + e.wireName() + "--> 期望 " + target + " 实得抛");
                    }
                }
            }
        }
        assertEquals(DeliveryState.values().length * DelegateEvent.values().length, checked);
        assertEquals("投递迁移表与手写基准不一致:\n" + join(mismatched), Collections.<String>emptyList(), mismatched);
    }

    /**
     * 每个事件要么在生命周期轴登记、要么在投递轴登记，
     * 要么显式声明是"只记账不迁移"（{@link DelegateEvent.Role#LEDGER_ONLY}）。
     * 防止"加了个枚举值其实谁都到不了它"这种 0 引用死符号再生产一次（§0 靶子四的病根）。
     */
    @Test
    public void everyEventHasADocumentedVerdict() {
        Map<DelegateEvent, Map<DelegateState, DelegateState>> life = transposeLifecycle();
        Map<DelegateEvent, Map<DeliveryState, DeliveryState>> deliv = transposeDelivery();
        List<String> homeless = new ArrayList<String>();
        for (DelegateEvent e : DelegateEvent.values()) {
            boolean inLife = life.containsKey(e);
            boolean inDeliv = deliv.containsKey(e);
            if (e.role() == DelegateEvent.Role.LEDGER_ONLY) {
                assertFalse(e + " 声明为 LEDGER_ONLY，却出现在生命周期表里", inLife);
                assertFalse(e + " 声明为 LEDGER_ONLY，却出现在投递表里", inDeliv);
                continue;
            }
            if (e.role() == DelegateEvent.Role.LIFECYCLE && !inLife) {
                homeless.add(e + " role=LIFECYCLE 但生命周期表里没有它的一行");
            }
            if (e.role() == DelegateEvent.Role.DELIVERY && !inDeliv) {
                homeless.add(e + " role=DELIVERY 但投递表里没有它的一行");
            }
            if (e.role() == DelegateEvent.Role.LIFECYCLE && inDeliv) {
                homeless.add(e + " role=LIFECYCLE 却动了投递轴（两轴串了）");
            }
            if (e.role() == DelegateEvent.Role.DELIVERY && inLife) {
                homeless.add(e + " role=DELIVERY 却动了生命周期轴（两轴串了）");
            }
        }
        assertEquals("有事件没落户:\n" + join(homeless), Collections.<String>emptyList(), homeless);
    }

    /** ★ P16 同型洞的守门人：没 claim 就想记 delivered，必须炸。 */
    @Test
    public void ackWithoutClaimIsIllegal() {
        for (DeliveryState from : DeliveryState.values()) {
            boolean legal = DelegateTransitions.canDeliveryTransition(from, DelegateEvent.RESULT_DELIVERED);
            assertEquals("只有 CLAIMED 才允许 ack（" + from + "）", from == DeliveryState.CLAIMED, legal);
        }
        try {
            DelegateTransitions.nextDelivery(DeliveryState.PENDING, DelegateEvent.RESULT_DELIVERED);
            throw new AssertionError("PENDING --RESULT_DELIVERED--> 必须抛，实得不抛");
        } catch (DelegateTransitions.IllegalTransitionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("delegate.result_delivered"));
        }
    }

    @Test
    public void terminalStatesHaveNoExitAtAll() {
        for (DelegateState s : DelegateState.values()) {
            int legal = DelegateTransitions.legalEventsFrom(s).size();
            if (s.terminal()) {
                assertEquals(s + " 是终态，出口数必须为 0", 0, legal);
            } else {
                assertFalse(s + " 非终态却有 0 出口（那它就永远推不动了）", legal == 0);
            }
        }
        for (DeliveryState s : DeliveryState.values()) {
            int legal = DelegateTransitions.legalDeliveryEventsFrom(s).size();
            if (s.terminal()) {
                assertEquals(s + " 是投递终态，出口数必须为 0", 0, legal);
            } else {
                assertFalse(s + " 非投递终态却有 0 出口", legal == 0);
            }
        }
    }

    @Test
    public void wireNamesAreUniqueAndRoundTrippable() {
        Set<String> seen = new LinkedHashSet<String>();
        for (DelegateEvent e : DelegateEvent.values()) {
            assertTrue("线名重复: " + e.wireName(), seen.add(e.wireName()));
            assertTrue(e.wireName() + " 必须以 delegate. 开头", e.wireName().startsWith("delegate."));
            assertEquals(e, DelegateEvent.fromWireName(e.wireName()));
        }
        assertEquals(seen, DelegateEvent.wireNames());
    }

    @Test
    public void legacyStatusLiteralsAreNormalizedOrRejected() {
        assertEquals(DelegateEvent.TASK_COMPLETED, DelegateEvent.fromLegacyStatus("DONE"));
        assertEquals(DelegateEvent.TASK_COMPLETED, DelegateEvent.fromLegacyStatus(" completed "));
        assertEquals(DelegateEvent.TASK_FAILED, DelegateEvent.fromLegacyStatus("error"));
        assertEquals(DelegateEvent.DISPATCH, DelegateEvent.fromLegacyStatus("QUEUED"));
        assertEquals(DelegateEvent.ORPHAN_ADOPTED, DelegateEvent.fromLegacyStatus("orphaned"));
        assertEquals(DelegateState.UNKNOWN, DelegateState.fromLegacy("unknown"));
        // 未知字面量不许静默变 null / 变默认值
        assertRejected(new Runnable() {
            @Override
            public void run() {
                DelegateEvent.fromLegacyStatus("OK");
            }
        });
        assertRejected(new Runnable() {
            @Override
            public void run() {
                DelegateEvent.fromLegacyStatus(null);
            }
        });
        assertRejected(new Runnable() {
            @Override
            public void run() {
                DelegateState.fromLegacy("");
            }
        });
    }

    @Test
    public void illegalTransitionsFailLoudWithBothSidesNamed() {
        try {
            DelegateTransitions.next(DelegateState.DONE, DelegateEvent.TASK_SPAWNED);
            throw new AssertionError("DONE 之后再 SPAWN 必须抛");
        } catch (DelegateTransitions.IllegalTransitionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("DONE"));
            assertTrue(e.getMessage(), e.getMessage().contains("delegate.task_spawned"));
            assertTrue(e.getMessage(), e.getMessage().contains("lifecycle"));
            assertEquals(DelegateEvent.TASK_SPAWNED, e.event());
            assertEquals(DelegateState.DONE, e.fromState());
        }
    }

    // ===== helpers =====

    private static Map<DelegateEvent, Map<DelegateState, DelegateState>> transposeLifecycle() {
        Map<DelegateEvent, Map<DelegateState, DelegateState>> out =
                new EnumMap<DelegateEvent, Map<DelegateState, DelegateState>>(DelegateEvent.class);
        for (Map.Entry<DelegateState, Map<DelegateEvent, DelegateState>> row : expectedLifecycle().entrySet()) {
            for (Map.Entry<DelegateEvent, DelegateState> cell : row.getValue().entrySet()) {
                Map<DelegateState, DelegateState> col = out.get(cell.getKey());
                if (col == null) {
                    col = new EnumMap<DelegateState, DelegateState>(DelegateState.class);
                    out.put(cell.getKey(), col);
                }
                col.put(row.getKey(), cell.getValue());
            }
        }
        return out;
    }

    private static Map<DelegateEvent, Map<DeliveryState, DeliveryState>> transposeDelivery() {
        Map<DelegateEvent, Map<DeliveryState, DeliveryState>> out =
                new EnumMap<DelegateEvent, Map<DeliveryState, DeliveryState>>(DelegateEvent.class);
        for (Map.Entry<DeliveryState, Map<DelegateEvent, DeliveryState>> row : expectedDelivery().entrySet()) {
            for (Map.Entry<DelegateEvent, DeliveryState> cell : row.getValue().entrySet()) {
                Map<DeliveryState, DeliveryState> col = out.get(cell.getKey());
                if (col == null) {
                    col = new EnumMap<DeliveryState, DeliveryState>(DeliveryState.class);
                    out.put(cell.getKey(), col);
                }
                col.put(row.getKey(), cell.getValue());
            }
        }
        return out;
    }

    private static void assertRejected(Runnable r) {
        try {
            r.run();
            throw new AssertionError("未知字面量必须抛，实得不抛");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            sb.append("  - ").append(x).append('\n');
        }
        return sb.toString();
    }

}
