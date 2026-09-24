package com.zifang.z.bot.agent;

/**
 * 流式事件订阅者。
 *
 * <p>z-bot 不引 Reactor：事件在调用线程上同步派发，
 * 终端通道直接边收边画，SSE 通道边收边 flush。</p>
 */
public interface StreamListener {

    void onEvent(StreamEvent event);

    StreamListener NOOP = new StreamListener() {
        @Override
        public void onEvent(StreamEvent event) {
        }
    };
}
