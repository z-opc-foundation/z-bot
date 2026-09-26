package com.zifang.z.bot.tool.env;

import com.zifang.z.bot.config.BotConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * 执行后端配置（P22）。
 *
 * <p><b>为什么不进 {@code BotConfig}：</b>{@code config/BotConfig.java} 同期在
 * {@code w6-p14}/{@code w7-p23} 两支线里被改，工单明令禁写。这里只<em>复用</em>它既有的
 * 取键原语 —— {@link BotConfig#defaultConfigDir()}（{@code -Dzbot.home > ZBOT_HOME > ~/.zbot}
 * 的单点解析，红线 1）和 {@code <profile>/config.properties} 这个文件名 ——
 * 读自己的 {@code exec.env.*} 键。<b>只读这一族键</b>，别的键（含 {@code minimax.api.key}）
 * 一律不碰、不打印、不进日志。</p>
 *
 * <p>缺省永远是 {@code local}，且 local 的行为要既有一致（回归见 EVIDENCE §2）。
 * 后端名写错 / docker 起不来 / ssh 没配 ⇒ 显式 {@link ExecEnvException}，
 * <b>不静默退 local</b>。</p>
 */
public final class ExecEnvConfig {

    public static final String KEY_BACKEND = "exec.env.backend";
    public static final String KEY_MAX_CONCURRENT = "exec.env.max.concurrent";
    public static final String KEY_DEFAULT_TIMEOUT_MS = "exec.env.default.timeout.ms";
    public static final String KEY_OUTPUT_MAX_BYTES = "exec.env.output.max.bytes";
    public static final String KEY_OUTPUT_POLICY = "exec.env.output.policy";

    public static final String KEY_DOCKER_HOST = "exec.env.docker.host";
    public static final String KEY_DOCKER_API_VERSION = "exec.env.docker.api.version";
    public static final String KEY_DOCKER_IMAGE = "exec.env.docker.image";
    public static final String KEY_DOCKER_MEMORY_MB = "exec.env.docker.memory.mb";
    public static final String KEY_DOCKER_CPUS = "exec.env.docker.cpus";
    public static final String KEY_DOCKER_PIDS_LIMIT = "exec.env.docker.pids.limit";
    public static final String KEY_DOCKER_NAME_PREFIX = "exec.env.docker.name.prefix";
    public static final String KEY_DOCKER_LEDGER = "exec.env.docker.ledger";
    public static final String KEY_DOCKER_NETWORK = "exec.env.docker.network";
    public static final String KEY_DOCKER_WORKDIR = "exec.env.docker.workdir";
    public static final String KEY_DOCKER_TIMEOUT_MS = "exec.env.docker.http.timeout.ms";

    public static final String KEY_SSH_BIN = "exec.env.ssh.bin";
    public static final String KEY_SSH_HOST = "exec.env.ssh.host";
    public static final String KEY_SSH_PORT = "exec.env.ssh.port";
    public static final String KEY_SSH_USER = "exec.env.ssh.user";
    public static final String KEY_SSH_IDENTITY = "exec.env.ssh.identity.file";
    public static final String KEY_SSH_WORKDIR = "exec.env.ssh.workdir";
    public static final String KEY_SSH_CONNECT_TIMEOUT_MS = "exec.env.ssh.connect.timeout.ms";

    public static final String DEFAULT_BACKEND = ExecEnvironment.LOCAL;
    public static final int DEFAULT_OUTPUT_MAX_BYTES = 262144;
    public static final int DEFAULT_MAX_CONCURRENT = 4;
    public static final String DEFAULT_DOCKER_API_VERSION = "v1.44";
    public static final String DEFAULT_DOCKER_NAME_PREFIX = "zbot-p22-";
    public static final String DEFAULT_SSH_BIN = "/usr/bin/ssh";

    private final File configDir;
    private final Properties props = new Properties();

    public ExecEnvConfig() {
        this(BotConfig.defaultConfigDir());
    }

    public ExecEnvConfig(File configDir) {
        this.configDir = configDir;
        File f = configDir == null ? null : new File(configDir, "config.properties");
        if (f != null && f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                Properties raw = new Properties();
                raw.load(in);
                // 只把 exec.env.* 这一族抄进来；其它键（密钥在内）根本不进这个对象
                for (String name : raw.stringPropertyNames()) {
                    if (name.startsWith("exec.env.")) {
                        props.setProperty(name, raw.getProperty(name));
                    }
                }
            } catch (Exception e) {
                System.err.println("[ExecEnvConfig] 读 " + f.getPath() + " 失败，按缺省走：" + e.getMessage());
            }
        }
    }

    public static ExecEnvConfig of(Properties onlyExecEnvKeys) {
        ExecEnvConfig c = new ExecEnvConfig(null);
        if (onlyExecEnvKeys != null) {
            for (String name : onlyExecEnvKeys.stringPropertyNames()) {
                if (name.startsWith("exec.env.")) {
                    c.props.setProperty(name, onlyExecEnvKeys.getProperty(name));
                }
            }
        }
        return c;
    }

    public File configDir() {
        return configDir;
    }

    public String raw(String key) {
        String sys = trim(System.getProperty(key));
        if (!sys.isEmpty()) {
            return sys;
        }
        String v = trim(props.getProperty(key));
        if (!v.isEmpty() && KEY_DOCKER_HOST.equals(key)) {
            return v;
        }
        if (!v.isEmpty()) {
            return v;
        }
        if (KEY_DOCKER_HOST.equals(key)) {
            String dh = trim(System.getenv("DOCKER_HOST"));
            if (!dh.isEmpty()) {
                return dh;
            }
            return "unix://" + System.getProperty("user.home") + "/.docker/run/docker.sock";
        }
        if (KEY_BACKEND.equals(key)) {
            String v2 = trim(System.getenv("ZBOT_EXEC_ENV_BACKEND"));
            if (!v2.isEmpty()) {
                return v2;
            }
            return DEFAULT_BACKEND;
        }
        return "";
    }

    public String backend() {
        String v = raw(KEY_BACKEND);
        return v.isEmpty() ? DEFAULT_BACKEND : v.toLowerCase();
    }

    public int outputMaxBytes() {
        return intOf(KEY_OUTPUT_MAX_BYTES, DEFAULT_OUTPUT_MAX_BYTES);
    }

    public int maxConcurrent() {
        return intOf(KEY_MAX_CONCURRENT, DEFAULT_MAX_CONCURRENT);
    }

    public long defaultTimeoutMs() {
        return longOf(KEY_DEFAULT_TIMEOUT_MS, 0L);
    }

    public ExecRequest.OutputPolicy outputPolicy() {
        String v = raw(KEY_OUTPUT_POLICY);
        if ("head".equals(v)) {
            return ExecRequest.OutputPolicy.HEAD;
        }
        if (v.isEmpty() || "window".equals(v)) {
            return ExecRequest.OutputPolicy.WINDOW;
        }
        // 写错不许猜：缺省是 window，但显式写错要能被人看见（由 factory 决定是硬失败还是告警）
        return ExecRequest.OutputPolicy.WINDOW;
    }

    // ---- docker ----

    public String dockerHost() {
        return raw(KEY_DOCKER_HOST);
    }

    public String dockerApiVersion() {
        String v = raw(KEY_DOCKER_API_VERSION);
        return v.isEmpty() ? DEFAULT_DOCKER_API_VERSION : v;
    }

    public String dockerImage() {
        return raw(KEY_DOCKER_IMAGE);
    }

    public long dockerMemoryBytes() {
        int mb = intOf(KEY_DOCKER_MEMORY_MB, 512);
        return mb <= 0 ? 0L : (long) mb * 1024L * 1024L;
    }

    public double dockerCpus() {
        String v = raw(KEY_DOCKER_CPUS);
        if (v.isEmpty()) {
            return 1.0d;
        }
        try {
            return Math.max(0.01d, Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return 1.0d;
        }
    }

    public int dockerPidsLimit() {
        return intOf(KEY_DOCKER_PIDS_LIMIT, 128);
    }

    public String dockerNamePrefix() {
        String v = raw(KEY_DOCKER_NAME_PREFIX);
        return v.isEmpty() ? DEFAULT_DOCKER_NAME_PREFIX : v;
    }

    public String dockerNetwork() {
        return raw(KEY_DOCKER_NETWORK);
    }

    public String dockerWorkdir() {
        String v = raw(KEY_DOCKER_WORKDIR);
        return v.isEmpty() ? "/workspace" : v;
    }

    public int dockerHttpTimeoutMs() {
        return intOf(KEY_DOCKER_TIMEOUT_MS, 15000);
    }

    /** 在飞容器台账（孤儿回收要用）；缺省落在 profile 目录里，绝不写 /tmp。 */
    public File dockerLedgerFile() {
        String v = raw(KEY_DOCKER_LEDGER);
        if (!v.isEmpty()) {
            return new File(v);
        }
        File base = configDir == null ? new File(System.getProperty("user.home"), ".zbot") : configDir;
        return new File(base, "tool-env/docker-ledger.properties");
    }

    // ---- ssh ----

    public String sshBin() {
        String v = raw(KEY_SSH_BIN);
        return v.isEmpty() ? DEFAULT_SSH_BIN : v;
    }

    public String sshHost() {
        return raw(KEY_SSH_HOST);
    }

    public int sshPort() {
        return intOf(KEY_SSH_PORT, 22);
    }

    public String sshUser() {
        return raw(KEY_SSH_USER);
    }

    public String sshIdentityFile() {
        return raw(KEY_SSH_IDENTITY);
    }

    public String sshWorkdir() {
        return raw(KEY_SSH_WORKDIR);
    }

    public int sshConnectTimeoutMs() {
        return intOf(KEY_SSH_CONNECT_TIMEOUT_MS, 5000);
    }

    // ---- 小工具 ----

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private int intOf(String key, int fallback) {
        String v = raw(key);
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            System.err.println("[ExecEnvConfig] " + key + "=" + v + " 不是整数，沿用 " + fallback);
            return fallback;
        }
    }

    private long longOf(String key, long fallback) {
        String v = raw(key);
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            System.err.println("[ExecEnvConfig] " + key + "=" + v + " 不是整数，沿用 " + fallback);
            return fallback;
        }
    }

    /** 配置摘要：只出「键=值形态」，任何看起来像凭据的值都打码。 */
    public String describe() {
        StringBuilder sb = new StringBuilder("exec.env{backend=");
        sb.append(backend()).append(", maxOut=").append(outputMaxBytes()).append("B")
                .append(", policy=").append(outputPolicy())
                .append(", timeoutMs=").append(defaultTimeoutMs());
        if (ExecEnvironment.DOCKER.equals(backend())) {
            sb.append(", docker.host=").append(maskIfCredentialLike(dockerHost()))
                    .append(", docker.image=").append(dockerImage())
                    .append(", docker.mem=").append(dockerMemoryBytes())
                    .append(", docker.cpus=").append(dockerCpus())
                    .append(", docker.pids=").append(dockerPidsLimit());
        }
        if (ExecEnvironment.SSH.equals(backend())) {
            sb.append(", ssh.host=").append(sshHost()).append(":").append(sshPort())
                    .append(", ssh.user=").append(sshUser().isEmpty() ? "(unset)" : "(set)")
                    .append(", ssh.identity=").append(sshIdentityFile().isEmpty() ? "(unset)" : "(set)");
        }
        return sb.append('}').toString();
    }

    private static String maskIfCredentialLike(String v) {
        if (v == null || v.isEmpty()) {
            return "(unset)";
        }
        int q = v.indexOf('@');
        if (q >= 0) {
            return "***@" + v.substring(q + 1);
        }
        return v;
    }
}
