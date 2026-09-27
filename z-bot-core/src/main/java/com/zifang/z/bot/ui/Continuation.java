package com.zifang.z.bot.ui;

/**
 * 行尾反斜杠续行 —— 唯一一条与终端协议无关的多行入口，JLine 与降级行模式共用这一个判定源。
 */
public final class Continuation {

    private Continuation() {
    }

    public static boolean endsWithContinuation(String buffer) {
        return buffer != null && buffer.endsWith("\\");
    }
}
