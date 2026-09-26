package com.zifang.z.bot.channel;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 入站请求体的唯一上限来源。四个入站面（飞书 / 钉钉 / webhook / HTTP 控制台）的 body 读取都只能走这里。
 *
 * 上限取 hermes 的 wecom 回调 {@code callback_adapter.py:57-59 _MAX_BODY = 65_536}（它在
 * {@code :147} 把 413 放在"任何签名工作之前"）。
 *
 * <p>门是**按字节累加**那一步，不是 {@code Content-Length}：头可以撒谎，分块传输根本没有这个头，
 * 只信头的实现能让超大 body 一路进内存。
 */
final class InboundLimits {

    static final int MAX_BODY_BYTES = 65_536;

    private InboundLimits() {
    }

    static byte[] readBodyBytes(HttpExchange ex) throws IOException {
        if (declaredLength(ex) > MAX_BODY_BYTES) {
            throw new BodyTooLargeException("body 超过上限 " + MAX_BODY_BYTES + " 字节");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int len;
        try (InputStream is = ex.getRequestBody()) {
            while ((len = is.read(buf)) != -1) {
                total += len;
                if (total > MAX_BODY_BYTES) {
                    throw new BodyTooLargeException("body 超过上限 " + MAX_BODY_BYTES + " 字节");
                }
                baos.write(buf, 0, len);
            }
        }
        return baos.toByteArray();
    }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(readBodyBytes(ex), StandardCharsets.UTF_8);
    }

    /** 只是省一趟读，不是门。缺失或非法都当 0，交给按字节累加那一步判。 */
    private static int declaredLength(HttpExchange ex) {
        String v = ex.getRequestHeaders().getFirst("Content-Length");
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
