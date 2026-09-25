package com.zifang.z.bot.channel;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** {@link PairingService} 码生成 / 消费 / 过期 单测。 */
public class PairingServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file;
    private PairingService service;

    @Before
    public void setUp() throws Exception {
        file = new File(tmp.newFolder("data"), "pairing.json");
        // fixture 用宽 TTL：懒清理是按 expiresAt 判定的一次写入即过期，200ms 意味着
        // "issue 落盘 + 下一步断言" 只要慢过 200ms 就被自己删掉（冷 JVM 下必挂）。
        // 过期语义只在 expiredCodesAreLazyCleaned 里用短 TTL 自建实例测。
        service = new PairingService(file, 60_000);
    }

    @Test
    public void issueAndConsumeRoundTrip() {
        PairingService.Pairing issued = service.issue("webhook", "conv-1", "alice");
        assertNotNull(issued.code);
        assertEquals(8, issued.code.length());
        assertEquals("webhook", issued.channel);

        PairingService.Pairing consumed = service.consume(issued.code);
        assertNotNull(consumed);
        assertEquals("conv-1", consumed.conversationId);
        assertEquals("alice", consumed.senderId);
        // 一次性
        assertEquals(null, service.consume(issued.code));
    }

    @Test
    public void caseInsensitiveCodeConsumption() {
        PairingService.Pairing issued = service.issue("webhook", "conv-1", "alice");
        assertNotNull(service.consume(issued.code.toLowerCase()));
    }

    @Test
    public void consumeReturnsNullForUnknownCode() {
        assertEquals(null, service.consume("NOT-A-CODE"));
    }

    @Test
    public void expiredCodesAreLazyCleaned() throws Exception {
        // expiresAt 由签发方按其 TTL 写入 ⇒ 必须用短 TTL 实例自己签发，宽 TTL 实例签的码
        // 换个小 TTL 读端也不会过期。3s（而非 200ms）是"未过期"这一侧的余量：懒清理按
        // now 判定，TTL 必须显著大于两次落盘 I/O 的尾延迟，否则又是构造出来的竞态。
        PairingService shortLived = new PairingService(file, 3000);
        shortLived.issue("webhook", "conv-1", "alice");
        assertTrue(shortLived.list().size() > 0);
        TimeUnit.MILLISECONDS.sleep(5000); // 超过 3s TTL
        // 触发一次访问触发懒清理
        assertEquals(0, shortLived.list().size());
        assertEquals(null, shortLived.consume("ANYCODE"));
    }

    @Test
    public void multipleIssuedCodesAccumulate() {
        PairingService.Pairing a = service.issue("webhook", "c1", "alice");
        PairingService.Pairing b = service.issue("feishu", "c2", "bob");
        assertFalse(a.code.equals(b.code));
        assertEquals(2, service.list().size());
        assertNotNull(service.consume(a.code));
        assertEquals(1, service.list().size());
    }

    @Test
    public void listIsUnmodifiable() {
        service.issue("webhook", "c1", "alice");
        try {
            service.list().clear();
            assertFalse("列表不可变", service.list().isEmpty());
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void persistedAcrossInstances() {
        PairingService.Pairing issued = service.issue("feishu", "conv-x", "carol");
        PairingService other = new PairingService(file, 60_000);
        assertEquals(1, other.list().size());
        assertNotNull(other.consume(issued.code));
    }
}