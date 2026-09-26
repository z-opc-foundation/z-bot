package com.zifang.z.bot.tool.env;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * 有界输出收集器（P22）—— hermes {@code tools/environments/base.py:54 _BoundedOutputCollector}
 * 的 Java 端口，字节口径。
 *
 * <p>照她的三件事：</p>
 * <ol>
 *   <li>{@code max_chars} 拆成 head/tail 两段窗口（她是 40%/60%，见 {@code base.py:60-61}）；</li>
 *   <li>{@code buffered_chars}（{@code :68}）= 实际留住的量，{@code total_chars}（{@code :73}）=
 *       <b>裁剪前</b>的全量，两者都要能问出来；</li>
 *   <li>{@code render()}（{@code :114}）在预算内拼省略标记，且必须给"要保留的状态后缀"腾地方。</li>
 * </ol>
 *
 * <p>没有上界的地方才是真危险：抽层之前 {@code BuiltinTools#bash} 是把子进程输出整段读进
 * {@code StringBuilder}（{@code BuiltinTools.java:465-477}），{@code maxChars} 只在读完之后裁剪 ——
 * 一条 {@code yes} 就能把堆撑爆。这里反过来：<b>收的时候</b>就只留得住的那部分。</p>
 */
public final class BoundedCollector {

    /** 全量捕获用的容量（她 {@code base.py:51 _UNBOUNDED_CAPTURE_CHARS = 2**63-1} 的同款手法：一条代码路径）。 */
    public static final int UNBOUNDED = Integer.MAX_VALUE;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final int maxBytes;
    private final ExecRequest.OutputPolicy policy;
    private final int headLimit;
    private final int tailLimit;

    private final ByteArrayOutputStream head = new ByteArrayOutputStream(1024);
    private byte[] tailRing;
    private int tailLen;
    private int tailPos;

    private long totalBytes;
    private long droppedBytes;
    private final Object lock = new Object();

    public BoundedCollector(int maxBytes, ExecRequest.OutputPolicy policy) {
        this.maxBytes = maxBytes <= 0 ? UNBOUNDED : maxBytes;
        this.policy = policy == null ? ExecRequest.OutputPolicy.WINDOW : policy;
        if (this.policy == ExecRequest.OutputPolicy.HEAD || this.maxBytes <= 8) {
            this.headLimit = this.maxBytes;
            this.tailLimit = 0;
        } else {
            // 她的比例：int(max_chars * 0.4) 头 + 其余尾
            this.headLimit = (int) (this.maxBytes * 0.4d);
            this.tailLimit = this.maxBytes - this.headLimit;
        }
        if (this.tailLimit > 0) {
            this.tailRing = new byte[this.tailLimit];
        }
    }

    public void append(byte[] buf, int off, int len) {
        if (buf == null || len <= 0) {
            return;
        }
        synchronized (lock) {
            totalBytes += len;
            int start = off;
            int remaining = len;

            if (head.size() < headLimit) {
                int take = Math.min(headLimit - head.size(), remaining);
                if (take > 0) {
                    head.write(buf, start, take);
                    start += take;
                    remaining -= take;
                }
            }
            if (remaining <= 0 || tailLimit <= 0) {
                droppedBytes += remaining;
                return;
            }
            if (remaining >= tailLimit) {
                // 这一段比整个尾窗口还长：前面攒的尾巴全部作废，只留这段的最后 tailLimit 字节
                System.arraycopy(buf, start + remaining - tailLimit, tailRing, 0, tailLimit);
                tailLen = tailLimit;
                tailPos = 0;
                droppedBytes += remaining - tailLimit;
                return;
            }
            for (int i = 0; i < remaining; i++) {
                if (tailLen == tailLimit) {
                    droppedBytes++;
                } else {
                    tailLen++;
                }
                tailRing[tailPos] = buf[start + i];
                tailPos = (tailPos + 1) % tailLimit;
            }
        }
    }

    public long totalBytes() {
        synchronized (lock) {
            return totalBytes;
        }
    }

    public long bufferedBytes() {
        synchronized (lock) {
            return (long) head.size() + tailLen;
        }
    }

    public long droppedBytes() {
        synchronized (lock) {
            return droppedBytes;
        }
    }

    public boolean truncated() {
        return droppedBytes() > 0;
    }

    public int maxBytes() {
        return maxBytes;
    }

    /** 不做省略标记的裸内容（头 + 尾），用于 HEAD 策略或上层要自己拼标记的场合。 */
    public String raw() {
        synchronized (lock) {
            byte[] all = new byte[head.size() + tailLen];
            byte[] hb = head.toByteArray();
            System.arraycopy(hb, 0, all, 0, hb.length);
            int copied = writeTailInto(all, hb.length);
            return decodeUtf8(all, 0, hb.length + copied);
        }
    }

    /** 渲染在预算内的文本；{@code suffix} 是必须留位置的状态后缀（她的 {@code render(suffix=...)}）。 */
    public String render(String suffix) {
        String tail = suffix == null ? "" : suffix;
        if (policy == ExecRequest.OutputPolicy.HEAD) {
            return raw() + tail;
        }
        synchronized (lock) {
            if (droppedBytes == 0) {
                return raw() + tail;
            }
            int available = Math.max(0, maxBytes - tail.length());
            String notice = "";
            for (int i = 0; i < 4; i++) {
                int contentBudget = Math.max(0, available - notice.length());
                int headChars = (int) (contentBudget * 0.4d);
                int tailChars = contentBudget - headChars;
                long omitted = Math.max(0, totalBytes - headChars - tailChars);
                String updated = "\n\n... [OUTPUT TRUNCATED - " + omitted
                        + " bytes omitted out of " + totalBytes + " total] ...\n\n";
                if (updated.equals(notice)) {
                    break;
                }
                notice = updated;
            }
            String body = raw();
            int contentBudget = Math.max(0, available - notice.length());
            int headChars = (int) (contentBudget * 0.4d);
            int tailChars = contentBudget - headChars;
            // 注意：这里是字符数预算（她也是字符口径），字节窗口只是粗筛
            if (notice.length() > available) {
                // 标记本身比预算还长：夹进预算里（她 render() 里也是 notice[:available]）
                notice = notice.substring(0, available);
            }
            String h = body.length() <= headChars ? body : body.substring(0, headChars);
            String t = "";
            if (tailChars > 0 && body.length() > h.length()) {
                String rest = body.substring(h.length());
                t = rest.length() <= tailChars ? rest : rest.substring(rest.length() - tailChars);
            }
            String rendered = h + notice + t + tail;
            return rendered.length() <= maxBytes ? rendered : rendered.substring(0, maxBytes);
        }
    }

    private int writeTailInto(byte[] dest, int offset) {
        if (tailLen == 0) {
            return 0;
        }
        int written = 0;
        // 环里从 tailPos 起（未满时）/ 从 tailPos 起（已满时即最旧）读出去
        int start = (tailLen == tailLimit) ? tailPos : 0;
        for (int i = 0; i < tailLen; i++) {
            dest[offset + written++] = tailRing[(start + i) % tailLimit];
        }
        return written;
    }

    /** UTF-8 解码：掐掉末尾可能被截断的多字节序列，别产出半个汉字。 */
    private static String decodeUtf8(byte[] data, int off, int len) {
        int safe = len;
        int scanned = 0;
        while (scanned < Math.min(4, len)) {
            byte b = data[off + len - 1 - scanned];
            if ((b & 0x80) == 0) {
                break;
            }
            if ((b & 0xC0) == 0xC0) {
                int need = (b & 0xFF) >= 0xF0 ? 4 : (b & 0xFF) >= 0xE0 ? 3 : 2;
                if (scanned + 1 < need) {
                    safe = len - 1 - scanned;
                }
                break;
            }
            scanned++;
        }
        return new String(data, off, safe, UTF_8);
    }
}
