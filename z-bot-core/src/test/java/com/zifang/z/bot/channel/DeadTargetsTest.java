package com.zifang.z.bot.channel;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DeadTargets}：只登记「整个会话已经死了」的目标，并且任何一次发送成功都要能自愈。
 *
 * <p>线程/话题级 not_found 绝不能登记 —— 话题被删不代表父会话死了，登记了就是把一条能用的
 * 会话永久短路。</p>
 */
public class DeadTargetsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file;

    private DeadTargets open(File at) {
        this.file = at;
        return new DeadTargets(at);
    }

    private DeadTargets fresh(String name) throws IOException {
        return open(new File(tmp.newFolder(name), "dead_targets.json"));
    }

    // ===== 1. 错误分类 =====

    @Test
    public void forbiddenErrorsAreDeadTargets() {
        for (String text : new String[] {
                "forbidden: the bot was blocked by the user",
                "Bad Request: user is deactivated",
                "Bad Request: chat was deleted",
                "Forbidden: bot has no rights to send messages",
                "Bad Request: bot is not a member of the chat",
        }) {
            assertEquals(text, DeadTargets.FORBIDDEN,
                    DeadTargets.classifySendError(new IOException(text), null));
        }
    }

    @Test
    public void onlyKnownStringsAreForbiddenKickedIsNot() {
        // 「被踢出群」不在 hermes 的 forbidden 名单里 —— 认不出就 UNKNOWN，
        // 宁可每次继续尝试投递（会失败但可见），也不要把一次误判变成永久短路。
        assertEquals(DeadTargets.UNKNOWN, DeadTargets.classifySendError(
                null, "Bad Request: bot was kicked from the group chat"));
        assertFalse(DeadTargets.isDeadErrorKind(DeadTargets.UNKNOWN));
    }

    @Test
    public void chatLevelNotFoundIsDeadButSubChatLevelIsNot() {
        assertEquals(DeadTargets.NOT_FOUND,
                DeadTargets.classifySendError(null, "Bad Request: chat not found"));
        assertTrue(DeadTargets.isChatLevelNotFound(null, "Bad Request: chat not found"));
        // 会话级与子会话级同时出现时按「不算整会话死」处理（宁可错杀不成漏放）
        String both = "chat not found; thread not found";
        assertEquals(both, DeadTargets.THREAD_NOT_FOUND, DeadTargets.classifySendError(null, both));
        assertFalse(DeadTargets.isChatLevelNotFound(null, both));
        for (String sub : new String[] {"thread not found", "message to reply not found",
                "message to edit not found", "topic_deleted", "message_id_invalid"}) {
            assertEquals(sub, DeadTargets.THREAD_NOT_FOUND, DeadTargets.classifySendError(null, sub));
        }
    }

    @Test
    public void unclassifiableFailuresAreUnknownAndNeverDead() {
        assertEquals(DeadTargets.UNKNOWN, DeadTargets.classifySendError(null, "平台 500 抖动"));
        assertEquals(DeadTargets.UNKNOWN, DeadTargets.classifySendError(null, null));
        assertEquals(DeadTargets.UNKNOWN, DeadTargets.classifySendError(null, "   "));
        assertEquals(DeadTargets.UNKNOWN, DeadTargets.classifySendError(new RuntimeException("x"), ""));
        assertFalse(DeadTargets.isDeadErrorKind(DeadTargets.UNKNOWN));
        assertFalse(DeadTargets.isDeadErrorKind(DeadTargets.THREAD_NOT_FOUND));
        assertTrue(DeadTargets.isDeadErrorKind(DeadTargets.FORBIDDEN));
        assertTrue(DeadTargets.isDeadErrorKind(DeadTargets.NOT_FOUND));
    }

    @Test
    public void classificationLooksAtExceptionMessageToo() {
        Exception wrapped = new IllegalStateException("HTTP 400",
                new IOException("Bad Request: chat not found"));
        assertEquals(DeadTargets.NOT_FOUND, DeadTargets.classifySendError(wrapped, null));
    }

    @Test
    public void causeChainIsReadUpToADepthCapAndNeverLoops() {
        // 真信号在第 3 层：通道层常见「RuntimeException -> IOException -> 平台错误」
        Exception threeDeep = new RuntimeException("send failed",
                new IllegalStateException("HTTP 400", new IOException("chat not found")));
        assertEquals(DeadTargets.NOT_FOUND, DeadTargets.classifySendError(threeDeep, null));

        // 真信号藏在封顶之外就不读了（防止有人拿无意义长链或自环把分类器拖死）
        RuntimeException beyond = new RuntimeException("chat not found");
        for (int i = 0; i < DeadTargets.MAX_CAUSE_DEPTH + 3; i++) {
            beyond = new RuntimeException("包装" + i, beyond);
        }
        assertEquals(DeadTargets.UNKNOWN, DeadTargets.classifySendError(beyond, null));

        // cause 环（JDK 只禁自指，a->b->a 这种两节点环是真能造出来的）不能把分类器打成死循环
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);
        assertEquals(DeadTargets.UNKNOWN, DeadTargets.classifySendError(a, null));
    }

    // ===== 2. 登记表本体 =====

    @Test
    public void markIsIdempotentAndReportsFirstTimeOnly() throws Exception {
        DeadTargets d = fresh("d1");
        assertTrue("首次登记", d.markDead("webhook", "chat-1", "chat not found"));
        assertFalse("重复登记只刷新不新建", d.markDead("webhook", "chat-1", "chat not found"));
        assertEquals(1, d.size());
        assertTrue(d.isDead("webhook", "chat-1"));
        assertFalse(d.isDead("webhook", "chat-2"));
        assertFalse("chatId 为空的目标无从登记", d.isDead("webhook", null));
        assertFalse(d.markDead("webhook", "", "x"));
    }

    @Test
    public void platformKeyIsCaseInsensitiveAndChatIsTrimmed() throws Exception {
        DeadTargets d = fresh("d2");
        d.markDead("FeiShu", "  oc_1  ", "kicked");
        assertTrue("平台名大小写不敏感", d.isDead("feishu", "oc_1"));
        assertEquals(1, d.size());
    }

    @Test
    public void successfulSendClearsTheMark() throws Exception {
        DeadTargets d = fresh("d3");
        d.markDead("webhook", "c1", "forbidden");
        assertTrue(d.clear("webhook", "c1"));
        assertFalse("没标记时 clear 报 false", d.clear("webhook", "c1"));
        assertFalse(d.isDead("webhook", "c1"));
        assertEquals(0, d.size());
    }

    @Test
    public void registrySurvivesAProcessRestart() throws Exception {
        File at = new File(tmp.newFolder("d4"), "dead_targets.json");
        DeadTargets first = open(at);
        first.markDead("dingtalk", "cid-9", "bot was blocked");
        DeadTargets reloaded = open(at);
        assertTrue("重启后仍然记得", reloaded.isDead("dingtalk", "cid-9"));
        Map<String, Object> entry = reloaded.allDead().get("dingtalk:cid-9");
        assertNotNull(entry);
        assertEquals("bot was blocked", entry.get("reason"));
        assertNotNull(entry.get("marked_at"));
        assertTrue(reloaded.allDead().get("dingtalk:cid-9").toString().contains("bot was blocked"));
    }

    @Test
    public void snapshotIsDefensive() throws Exception {
        DeadTargets d = fresh("d5");
        d.markDead("webhook", "c1", "x");
        Map<String, Map<String, Object>> snap = d.allDead();
        try {
            snap.put("hack", null);
            throw new AssertionError("快照必须不可变");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        assertEquals(1, d.size());
    }

    @Test
    public void corruptRegistryDegradesToEmptyNotThrow() throws Exception {
        File at = new File(tmp.newFolder("d6"), "dead_targets.json");
        Files.write(at.toPath(), "{ this is not json".getBytes(StandardCharsets.UTF_8));
        DeadTargets d = open(at);
        assertEquals(0, d.size());
        assertFalse(d.isDead("webhook", "c1"));
        // 坏文件不挡新登记
        assertTrue(d.markDead("webhook", "c1", "chat not found"));
        assertTrue(d.isDead("webhook", "c1"));
    }

    @Test
    public void unwritablePathDegradesToInMemory() throws Exception {
        File parent = tmp.newFolder("d7");
        File blocker = new File(parent, "blocker");
        assertTrue(blocker.createNewFile());
        // 父路径是一个普通文件 ⇒ mkdirs 必然失败 ⇒ 落盘失败不许连带丢内存登记、更不许抛
        DeadTargets d = open(new File(blocker, "nested/dead_targets.json"));
        assertEquals(0, d.size());
        assertTrue("落盘坏不了内存登记", d.markDead("webhook", "c1", "chat not found"));
        assertTrue(d.isDead("webhook", "c1"));
        assertFalse("登记表坏了也不能影响别的目标", d.isDead("webhook", "c2"));
    }

    @Test
    public void forConfigDirPutsTheFileUnderTheProfile() throws Exception {
        File configDir = tmp.newFolder("profile-77");
        DeadTargets d = DeadTargets.forConfigDir(configDir);
        assertEquals(new File(configDir, "gateway/dead_targets.json").getCanonicalFile(),
                d.path().getCanonicalFile());
        d.markDead("webhook", "c1", "x");
        assertTrue("必须落在 profile 里（红线 1）",
                d.path().getCanonicalPath().startsWith(configDir.getCanonicalPath()));
        assertNull("无 profile 时不落盘", DeadTargets.forConfigDir(null).path());
    }

    @Test
    public void reasonIsCapped() throws Exception {
        DeadTargets d = fresh("d8");
        StringBuilder longReason = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longReason.append("0123456789");
        }
        d.markDead("webhook", "c1", longReason.toString());
        String stored = String.valueOf(d.allDead().get("webhook:c1").get("reason"));
        assertEquals("原因截到 200 字符", 200, stored.length());
    }
}
