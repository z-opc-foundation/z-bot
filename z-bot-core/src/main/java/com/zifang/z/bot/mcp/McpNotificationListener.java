package com.zifang.z.bot.mcp;

import java.util.Collections;
import java.util.Map;

/**
 * MCP server <b>主动推</b>的 notification 回调（不是对某个 request 的应答）。
 *
 * <p>为什么需要这个接口：内核 {@code StdioMcpTransport} 的读循环是
 * {@code while (now < deadline) { line = stdout.readLine(); if (!line.contains("\"id\":" + id)) continue; }}
 * —— 它只在"有人在等某个 id"时才去读，且不匹配的行<b>直接丢弃</b>。于是 server 主动推的
 * {@code notifications/tools/list_changed} 结构上到不了上层（P21 §2 有实测取证）。
 * z-bot 侧自己的 transport 用常驻读线程 + 按 id 派发补上这条通道。</p>
 *
 * <p>实现方<b>必须</b>在独立线程上回调，不能在 transport 自己的读线程里调：
 * 回调体（{@link McpBridge#refreshTools()}）会再发一次 {@code tools/list}，
 * 若跑在读线程上就是自锁。</p>
 */
public interface McpNotificationListener {

    /** 规范里唯一会影响注册表的那条通知。 */
    String TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

    /**
     * @param method notification 的 method，非空
     * @param params notification 的 params，可能为空集合但不会是 null
     */
    void onNotification(String method, Map<String, Object> params);

    /** 空实现：用于"这条通道不接通知"的占位。 */
    McpNotificationListener NOOP = new McpNotificationListener() {
        @Override
        public void onNotification(String method, Map<String, Object> params) {
        }
    };

    /** params 缺失时统一给不可变空表，省得每处判 null。 */
    static Map<String, Object> emptyParams() {
        return Collections.emptyMap();
    }
}
