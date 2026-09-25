package com.zifang.z.bot.channel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DingTalkChannel} 加签 URL + SHA-1 工具的纯算法单测
 * （不依赖网络/HTTP server，跑得快）。
 */
public class DingTalkChannelSignTest {

    @Test
    public void signedUrlHasTimestampAndSignQuery() {
        DingTalkChannel ch = new DingTalkChannel(null, 0, "https://oapi.dingtalk.com/robot/send?access_token=xxx", "SECabc");
        String url = ch.signedWebhookUrl();
        assertTrue("URL 含 timestamp 参数: " + url, url.contains("timestamp="));
        assertTrue("URL 含 sign 参数: " + url, url.contains("sign="));
        assertTrue("URL 含原始 access_token: " + url, url.contains("access_token=xxx"));
    }

    @Test
    public void signedUrlNoSecretNoChange() {
        DingTalkChannel ch = new DingTalkChannel(null, 0,
                "https://oapi.dingtalk.com/robot/send?access_token=xxx", "");
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=xxx", ch.signedWebhookUrl());
    }

    @Test
    public void differentSecretsProduceDifferentSignatures() {
        DingTalkChannel a = new DingTalkChannel(null, 0, "https://x", "SEC1");
        DingTalkChannel b = new DingTalkChannel(null, 0, "https://x", "SEC2");
        // timestamp 同毫秒 → sign 应不同
        String ua = a.signedWebhookUrl();
        String ub = b.signedWebhookUrl();
        assertNotEquals(ua, ub);
    }

    @Test
    public void sha1HexKnownValue() {
        // 已知常量: sha1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", DingTalkChannel.sha1Hex("abc"));
        // sha1("") = da39a3ee5e6b4b0d3255bfef95601890afd80709
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", DingTalkChannel.sha1Hex(""));
    }

    @Test
    public void sha1HexEmptyString() {
        // sha1("") = da39a3ee5e6b4b0d3255bfef95601890afd80709
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", DingTalkChannel.sha1Hex(""));
    }
}