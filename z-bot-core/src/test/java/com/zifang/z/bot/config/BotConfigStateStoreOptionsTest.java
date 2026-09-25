package com.zifang.z.bot.config;

import com.zifang.z.bot.store.StateStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P15 接线：{@code StateStore.Options.configKeys()} 里列出的每个键都必须真被读到。
 *
 * <p>这份清单是"代理留给主编的接线点"，最容易出的事故是键名登记了、映射漏了 ——
 * 用户写了配置但行为不变，而且没有任何东西会变红。所以这里把清单本身当分母：
 * 少一个键的断言 ⇒ 立刻红。</p>
 */
public class BotConfigStateStoreOptionsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** key → 一个"与默认值不同、且能出现在快照里"的取值。 */
    private static final Map<String, String> SAMPLE = new LinkedHashMap<String, String>();
    /** key → 期望快照里出现的片段。 */
    private static final Map<String, String> EXPECT = new LinkedHashMap<String, String>();

    static {
        put("agent.state.db.busy.timeout.ms", "321", "busy=321");
        put("agent.state.db.write.retries", "3", "retries=3");
        put("agent.state.db.write.retry.min.ms", "11", "min=11");
        put("agent.state.db.write.retry.max.ms", "151", "max=151");
        put("agent.state.db.write.begin.immediate", "false", "immediate=false");
        put("agent.state.db.checkpoint.every.n", "7", "ckpt=7");
        put("agent.state.db.auto.recover.corrupt", "0", "recover=false");
        put("agent.state.db.verify.on.open", "no", "verify=false");
        put("agent.state.prune.auto", "yes", "prune=true");
        put("agent.state.prune.retention.days", "33", "days=33");
    }

    private static void put(String key, String value, String expect) {
        SAMPLE.put(key, value);
        EXPECT.put(key, expect);
    }

    /** 把 10 个参数摊成一行，便于"只有被接线的那个字段变了"这种断言。 */
    private static String snapshot(StateStore.Options o) {
        return "busy=" + o.busyTimeoutMillis()
                + " retries=" + o.writeRetries()
                + " min=" + o.retryMinMillis()
                + " max=" + o.retryMaxMillis()
                + " immediate=" + o.beginImmediate()
                + " ckpt=" + o.checkpointEveryNWrites()
                + " recover=" + o.autoRecoverCorrupt()
                + " verify=" + o.verifyOnOpen()
                + " prune=" + o.autoPrune()
                + " days=" + o.retentionDays();
    }

    private BotConfig loadWith(String body) throws Exception {
        File dir = folder.newFolder("cfg" + System.nanoTime());
        Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(new File(dir, "config.properties")), "UTF-8");
        try {
            w.write(body);
        } finally {
            w.close();
        }
        return BotConfig.load(dir);
    }

    @Test
    public void everyRegisteredKeyHasACase() {
        for (String key : StateStore.Options.configKeys()) {
            assertTrue("configKeys() 登记了但没接线（或测试没钉）: " + key, SAMPLE.containsKey(key));
        }
        assertEquals("清单长度和 configKeys() 必须一致",
                StateStore.Options.configKeys().size(), SAMPLE.size());
    }

    @Test
    public void eachKeyAloneMovesExactlyItsOwnField() throws Exception {
        String base = snapshot(StateStore.Options.defaults());
        for (Map.Entry<String, String> e : SAMPLE.entrySet()) {
            StateStore.Options o = loadWith(e.getKey() + "=" + e.getValue() + "\n").stateStoreOptions();
            String snap = snapshot(o);
            assertTrue(e.getKey() + " 没生效，快照仍是 " + snap, snap.contains(EXPECT.get(e.getKey())));
            assertEquals(e.getKey() + " 连带改动了别的字段: " + snap,
                    differOnly(base, snap, EXPECT.get(e.getKey())), Boolean.TRUE);
        }
    }

    /** 除了 expect 那一段，其余字段必须与默认一致。 */
    private static Boolean differOnly(String base, String snap, String expect) {
        String[] a = base.split(" ");
        String[] b = snap.split(" ");
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            boolean expectedToChange = b[i].equals(expect) || a[i].substring(0, a[i].indexOf('=') + 1)
                    .equals(expect.substring(0, expect.indexOf('=') + 1));
            if (!a[i].equals(b[i]) && !expectedToChange) {
                return false;
            }
        }
        return true;
    }

    @Test
    public void garbageValueFallsBackToStoreDefault() throws Exception {
        String base = snapshot(StateStore.Options.defaults());
        assertEquals(base, snapshot(loadWith("agent.state.db.write.retries=lots\n").stateStoreOptions()));
        assertEquals(base, snapshot(loadWith("agent.state.prune.auto=maybe\n").stateStoreOptions()));
        assertEquals(base, snapshot(loadWith("agent.state.db.busy.timeout.ms=\n").stateStoreOptions()));
    }

    @Test
    public void absentConfigKeepsStoreDefaults() throws Exception {
        assertEquals(snapshot(StateStore.Options.defaults()),
                snapshot(loadWith("agent.name=zifa\n").stateStoreOptions()));
    }
}
