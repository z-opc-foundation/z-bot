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
        // TTL 短一些好测过期
        service = new PairingService(file, 200);
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
        service.issue("webhook", "conv-1", "alice");
        TimeUnit.SECONDS.sleep(1); // 超过 200ms TTL
        // 触发一次访问触发懒清理
        assertEquals(0, service.list().size());
        assertEquals(null, service.consume("ANYCODE"));
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