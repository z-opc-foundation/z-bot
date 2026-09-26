package com.zifang.z.bot.acp;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ACP（Agent Client Protocol）wire 方法名常量。
 *
 * <h2>出处（不是凭记忆，也不是照抄工单的字面量计数）</h2>
 * hermes 的适配器里没有裸字面量方法名，是因为协议方法走 SDK 派发表。权威出处 =
 * 她 import 的那个 SDK：{@code agent-client-protocol==0.9.0}
 * （{@code ~/.hermes/hermes-agent/pyproject.toml:221}），派发表在其
 * {@code acp/meta.py:3—28}（该文件头两行注明 {@code # Generated from schema/meta.json. Do not
 * edit by hand.} 与 {@code # Schema ref: refs/tags/v0.11.2}）。本表逐条抄自那份 meta.py，
 * 实测过程见 {@code _doc/acceptance/p25/EVIDENCE.md} §0.2。
 *
 * <h2>两套方向的差别</h2>
 * <ul>
 *   <li>{@link #AGENT_METHODS}：客户端 → agent（13 条）。z-bot 在这一面是 <b>server</b>。</li>
 *   <li>{@link #CLIENT_METHODS}：agent → 客户端（9 条）。z-bot 在这一面是 <b>caller</b>，
 *       本期只真用 {@link #SESSION_UPDATE} 与 {@link #SESSION_REQUEST_PERMISSION}。</li>
 * </ul>
 *
 * <p>{@code session/cancel} 在 schema 里是<b>通知</b>（客户端不等回包），其余 agent 方法都是请求；
 * 这一区分在 {@code AcpAgentServer} 的分派里落地，不在本类里编码，避免"常量表变成第二份真身"。</p>
 */
public final class AcpMethods {

    /** 协议版本：{@code acp/meta.py:29} 实测 {@code PROTOCOL_VERSION = 1}。 */
    public static final int PROTOCOL_VERSION = 1;

    // ---- agent 面（客户端 → z-bot）----
    public static final String INITIALIZE = "initialize";
    public static final String AUTHENTICATE = "authenticate";
    public static final String SESSION_NEW = "session/new";
    public static final String SESSION_LOAD = "session/load";
    public static final String SESSION_PROMPT = "session/prompt";
    public static final String SESSION_CANCEL = "session/cancel";
    public static final String SESSION_FORK = "session/fork";
    public static final String SESSION_LIST = "session/list";
    public static final String SESSION_RESUME = "session/resume";
    public static final String SESSION_CLOSE = "session/close";
    public static final String SESSION_SET_MODE = "session/set_mode";
    public static final String SESSION_SET_MODEL = "session/set_model";
    public static final String SESSION_SET_CONFIG_OPTION = "session/set_config_option";

    // ---- client 面（z-bot → 客户端）----
    public static final String SESSION_UPDATE = "session/update";
    public static final String SESSION_REQUEST_PERMISSION = "session/request_permission";
    public static final String FS_READ_TEXT_FILE = "fs/read_text_file";
    public static final String FS_WRITE_TEXT_FILE = "fs/write_text_file";
    public static final String TERMINAL_CREATE = "terminal/create";
    public static final String TERMINAL_OUTPUT = "terminal/output";
    public static final String TERMINAL_RELEASE = "terminal/release";
    public static final String TERMINAL_WAIT_FOR_EXIT = "terminal/wait_for_exit";
    public static final String TERMINAL_KILL = "terminal/kill";

    /** 13 条，顺序与 {@code acp/meta.py} 的 {@code AGENT_METHODS} 一致。 */
    public static final Set<String> AGENT_METHODS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    AUTHENTICATE, INITIALIZE, SESSION_CANCEL, SESSION_CLOSE, SESSION_FORK,
                    SESSION_LIST, SESSION_LOAD, SESSION_NEW, SESSION_PROMPT, SESSION_RESUME,
                    SESSION_SET_CONFIG_OPTION, SESSION_SET_MODE, SESSION_SET_MODEL)));

    /** 9 条，顺序与 {@code acp/meta.py} 的 {@code CLIENT_METHODS} 一致。 */
    public static final Set<String> CLIENT_METHODS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    FS_READ_TEXT_FILE, FS_WRITE_TEXT_FILE, SESSION_REQUEST_PERMISSION,
                    SESSION_UPDATE, TERMINAL_CREATE, TERMINAL_KILL, TERMINAL_OUTPUT,
                    TERMINAL_RELEASE, TERMINAL_WAIT_FOR_EXIT)));

    // ---- session/update 的 sessionUpdate 判别字（acp/schema.py 各 alias 实测）----
    public static final String UPDATE_AGENT_MESSAGE_CHUNK = "agent_message_chunk";
    public static final String UPDATE_AGENT_THOUGHT_CHUNK = "agent_thought_chunk";
    public static final String UPDATE_USER_MESSAGE_CHUNK = "user_message_chunk";
    public static final String UPDATE_TOOL_CALL = "tool_call";
    public static final String UPDATE_TOOL_CALL_UPDATE = "tool_call_update";
    public static final String UPDATE_PLAN = "plan";
    public static final String UPDATE_AVAILABLE_COMMANDS = "available_commands_update";

    // ---- stopReason（acp/schema.py:14 的 StopReason 字面量集）----
    public static final String STOP_END_TURN = "end_turn";
    public static final String STOP_MAX_TOKENS = "max_tokens";
    public static final String STOP_REFUSAL = "refusal";
    public static final String STOP_CANCELLED = "cancelled";

    /**
     * 权限选项 id（沿用她的 {@code acp_adapter/permissions.py:21—27} 稳定 id 集），
     * 五档正好落在 z-bot {@code ApprovalService.Resolution} 的四档上。
     */
    public static final String OPTION_ALLOW_ONCE = "allow_once";
    public static final String OPTION_ALLOW_SESSION = "allow_session";
    public static final String OPTION_ALLOW_ALWAYS = "allow_always";
    public static final String OPTION_DENY = "deny";
    public static final String OPTION_DENY_ALWAYS = "deny_always";

    private static final Set<String> RESERVED_AGENT_NAMESPACE =
            Collections.unmodifiableSet(new LinkedHashSet<String>(AGENT_METHODS));

    private AcpMethods() {
    }

    /** 该方法名是否属于 ACP agent 面（用于把"未知方法"与"已知但未实现"分开报出来）。 */
    public static boolean isAgentMethod(String method) {
        return method != null && RESERVED_AGENT_NAMESPACE.contains(method);
    }
}
