package com.zifang.z.bot.tool.env;

import java.io.IOException;

/**
 * 后端/配置层的显式错误（P22）。
 *
 * <p>存在的理由就一条：抽层之后最怕「后端不行就当没事发生」。
 * hermes 那边 docker/ssh 起不来是直接抛的；这边如果用 {@code local} 兜底，
 * 就会把一个「本该在容器里跑」的命令放进宿主 shell —— 沙箱语义整个反了。
 * 所以 {@link Code#BACKEND_SELECTION_FAILED} 一类一律炸，不降级。</p>
 */
public class ExecEnvException extends IOException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        /** 配置里的后端名不认识 / 后端不可用 ⇒ 显式失败，绝不静默退 local。 */
        BACKEND_SELECTION_FAILED,
        /** docker daemon 连不上（unix socket 不存在、tcp 拒连、HTTP 非 2xx）。 */
        DOCKER_UNAVAILABLE,
        /** docker 回的不是我这个服务（404/HTML/形状不对）—— 连上和"是我那个 daemon"是两件事。 */
        DOCKER_BAD_RESPONSE,
        /** ssh 目标主机不在配置里 / 含非法字符。 */
        SSH_TARGET_NOT_CONFIGURED,
        /** ssh 无凭据（密钥文件不存在、BatchMode 下要口令）。 */
        SSH_NO_CREDENTIALS,
        /** ssh 命令被拼成了字符串（本后端只允许 argv + 逐 token 转义）。 */
        SSH_COMMAND_CONCAT_FORBIDDEN,
        /** 路径越出后端沙箱根。 */
        SANDBOX_ESCAPE,
        /** 后端不支持这个能力（如 docker 不收 stdin）。 */
        CAPABILITY_UNSUPPORTED
    }

    private final Code code;

    public ExecEnvException(Code code, String message) {
        super("[" + code.name() + "] " + message);
        this.code = code;
    }

    public ExecEnvException(Code code, String message, Throwable cause) {
        super("[" + code.name() + "] " + message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /** 给工具/日志用的短码字符串。 */
    public String codeName() {
        return code.name();
    }
}
