package com.zifang.z.bot.tool.env;

import com.zifang.z.bot.tool.Sandbox;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 后端选择与持有（P22）。
 *
 * <p>{@link #create} 只认 {@code local / docker / ssh} 三个名字。
 * <b>名字不认识、后端起不来 ⇒ 直接抛</b>，一条兜底路径都不留：
 * "选后端失败就退 local" 听上去稳妥，实际是把本该关在容器/远端的命令放进宿主 shell，
 * 沙箱语义整个反了（工单 §1.2 明确禁这条，变异体
 * {@code BACKEND_SILENT_FALLBACK} 专门打它）。</p>
 *
 * <p>{@link #current()} 是接线点：{@code BuiltinTools} 的 exec 那一路从这里取后端。
 * 这根线断没断由 {@code BuiltinToolsExecWiringTest} 守（变异体 {@code WIRING_DETACHED}）。</p>
 */
public final class ExecEnvironments {

    private static volatile ExecEnvironment installed;

    private ExecEnvironments() {
    }

    /** 按配置造后端；{@code sandbox} 给 local 当沙箱根，docker/ssh 用它做路径基准。 */
    public static ExecEnvironment create(ExecEnvConfig config, Sandbox sandbox) throws IOException {
        ExecEnvConfig cfg = config == null ? new ExecEnvConfig() : config;
        String backend = cfg.backend();
        if (backend == null || backend.trim().isEmpty()) {
            throw new ExecEnvException(ExecEnvException.Code.BACKEND_SELECTION_FAILED,
                    ExecEnvConfig.KEY_BACKEND + " 是空串 —— 显式失败，不退回 local");
        }
        String normalized = backend.trim().toLowerCase(Locale.ROOT);
        if (ExecEnvironment.LOCAL.equals(normalized)) {
            return new LocalExecEnvironment(cfg, sandbox);
        }
        if (ExecEnvironment.DOCKER.equals(normalized)) {
            return new DockerExecEnvironment(cfg);
        }
        if (ExecEnvironment.SSH.equals(normalized)) {
            File root = sandbox == null ? new File(cfg.sshWorkdir()) : sandbox.root();
            return new SshExecEnvironment(cfg, root);
        }
        throw new ExecEnvException(ExecEnvException.Code.BACKEND_SELECTION_FAILED,
                "不认识的后端名：" + normalized + "（只支持 local/docker/ssh）。"
                        + "这里不许静默换成 " + ExecEnvConfig.DEFAULT_BACKEND + " —— 见 EVIDENCE §1");
    }

    /**
     * 缺省后端（缺配置 = local）。
     *
     * <p>沙箱根由<b>调用方</b>给；这里刻意不自己 {@code new Sandbox(null)} —— 那条路会解析到
     * {@code ~/.zbot/workspace} 并在缺省时把它 mkdir 出来，测试里等于往真 profile 写东西
     * （红线 3/杠④）。调用方没给沙箱（{@code curl_test} 就是）时传 null，
     * 于是文件类操作显式报 {@code SANDBOX_ESCAPE}，而不是悄悄落到 profile 里。</p>
     */
    public static ExecEnvironment defaultEnvironment(Sandbox sandbox) throws IOException {
        ExecEnvConfig cfg = new ExecEnvConfig();
        return create(cfg, sandbox);
    }

    /**
     * 接线点：拿当前后端。
     *
     * <p>首次调用惰性建一个 {@code local}（与今天的行为一致），之后一直复用；
     * 测试/E2E 用 {@link #install} 换实现。</p>
     */
    public static ExecEnvironment current(Sandbox sandbox) throws IOException {
        ExecEnvironment e = installed;
        if (e != null) {
            return e;
        }
        synchronized (ExecEnvironments.class) {
            if (installed == null) {
                installed = defaultEnvironment(sandbox);
            }
            return installed;
        }
    }

    /** 没有沙箱上下文时的取法（等价于 {@code current(null)}）。 */
    public static ExecEnvironment current() throws IOException {
        return current(null);
    }

    /** 测试/E2E 的注入缝（传 null = 清掉，下一次 {@link #current()} 重新按配置建）。 */
    public static void install(ExecEnvironment environment) {
        if (environment != null) {
            closeQuietly(installed);
        }
        installed = environment;
    }

    public static boolean isInstalled() {
        return installed != null;
    }

    private static void closeQuietly(ExecEnvironment e) {
        if (e != null) {
            try {
                e.close();
            } catch (IOException ignored) {
                // 换后端时旧的关不掉不阻断，但要说出来
                System.err.println("[ExecEnvironments] 关闭旧后端失败：" + e.name() + " " + ignored.getMessage());
            }
        }
    }
}
