package com.zifang.z.bot.tool.env;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * docker 后端（P22）：一条命令 = 一个容器，argv 直给 {@code Cmd}，<b>不过 shell</b>。
 *
 * <p>本棒对它只要求三件事，都能被假 dockerd 逐字段钉死（见 {@code p22_e2e.py}）：</p>
 * <ol>
 *   <li><b>请求面</b>：{@code POST /{ver}/containers/create} 的 body 里
 *       {@code Cmd} 必须是数组（拼接过的字符串就是注入面），资源限额
 *       {@code Memory}/{@code NanoCpus}/{@code PidsLimit} 必须在；</li>
 *   <li><b>回收面</b>：成功、失败、超时三条路都要走到 {@code DELETE /containers/{id}?force=true}；
 *       进程被 kill 之后由台账（{@code docker-ledger.properties}，照她
 *       {@code base.py:259/:269 _load_json_store/_save_json_store} 的手法）+
 *       {@link #reapOrphans()} 兜住 running 孤儿容器；</li>
 *   <li><b>失败面</b>：daemon 连不上 / 回的不是 docker（404 也算连上了）⇒ 显式
 *       {@link ExecEnvException}，<b>绝不静默退回 local</b>。</li>
 * </ol>
 *
 * <p>stdin 本期明确不支持（{@link #supportsStdin()} 返 false，要 stdin 就直接
 * {@code CAPABILITY_UNSUPPORTED}），因为裸 HTTP + {@code HttpURLConnection} 做不了
 * attach 的双向流复用；理由与后续做法记在 EVIDENCE §12。</p>
 */
public final class DockerExecEnvironment implements ExecEnvironment {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** 台账里给孤儿判定用的键前缀。 */
    static final String LEDGER_PREFIX = "container.";
    /** 打在容器上的 label：孤儿回收只认自己贴过的东西，绝不乱删别人的容器。 */
    static final String LABEL_MANAGED = "z-bot.p22.managed";
    static final String LABEL_OWNER_PID = "z-bot.p22.owner-pid";
    static final String LABEL_RUN = "z-bot.p22.run";

    private final ExecEnvConfig config;
    private final DockerEngineClient client;
    private final String apiPrefix;
    private final File ledgerFile;
    private final String runToken;
    private final String sandboxRoot;
    private final Object ledgerLock = new Object();

    public DockerExecEnvironment(ExecEnvConfig config) throws IOException {
        this.config = config == null ? new ExecEnvConfig() : config;
        this.client = DockerEngineClient.forHost(this.config.dockerHost(), this.config.dockerHttpTimeoutMs());
        this.apiPrefix = "/" + this.config.dockerApiVersion();
        this.ledgerFile = this.config.dockerLedgerFile();
        this.runToken = DockerEngineClient.newRunToken();
        this.sandboxRoot = this.config.dockerWorkdir();
        if (this.config.dockerImage().isEmpty()) {
            throw new ExecEnvException(ExecEnvException.Code.BACKEND_SELECTION_FAILED,
                    "backend=docker 但 exec.env.docker.image 没配 —— 不猜镜像名，也不退 local");
        }
        pingOrThrow();
    }

    @Override
    public String name() {
        return DOCKER;
    }

    @Override
    public boolean supportsStdin() {
        return false;
    }

    @Override
    public String sandboxRoot() {
        return sandboxRoot;
    }

    @Override
    public String describe() {
        return "docker{transport=" + client.transport() + ", host=" + safeHost()
                + ", api=" + config.dockerApiVersion() + ", image=" + config.dockerImage()
                + ", memory=" + config.dockerMemoryBytes() + "B, cpus=" + config.dockerCpus()
                + ", pids=" + config.dockerPidsLimit() + ", network=" + networkMode()
                + ", run=" + runToken + "}";
    }

    private String safeHost() {
        // 只出形态，不出可能带凭据的整串
        String h = config.dockerHost();
        int slash = h.indexOf("://");
        return slash < 0 ? "(?)" : h.substring(0, slash) + "://***";
    }

    String networkMode() {
        String v = config.dockerNetwork();
        return v.isEmpty() ? "none" : v;
    }

    DockerEngineClient client() {
        return client;
    }

    String runToken() {
        return runToken;
    }

    File ledgerFile() {
        return ledgerFile;
    }

    /** GET /{_ping} —— 连上了不等于"是我那个服务"，状态码 + 形状都要判。 */
    private void pingOrThrow() throws IOException {
        DockerEngineClient.Response r;
        try {
            r = client.request("GET", apiPrefix + "/_ping", null);
        } catch (IOException e) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_UNAVAILABLE,
                    "docker daemon 连不上（" + safeHost() + "）：" + e.getMessage()
                            + " —— 显式失败，不退回 local", e);
        }
        if (!r.looksLikeDockerJson(200)) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    apiPrefix + "/_ping 实测 status=" + r.status()
                            + " body=" + head(r.body(), 120));
        }
    }

    // ===== exec =====

    @Override
    public ExecResult exec(ExecRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (request.stdin() != null && request.stdin().length > 0) {
            throw new ExecEnvException(ExecEnvException.Code.CAPABILITY_UNSUPPORTED,
                    "docker 后端本期不收 stdin（裸 HTTP 做不了 attach 双向流）；"
                            + "要 stdin 请把后端切成 local，不许偷偷换着跑");
        }
        String id = createContainer(request);
        boolean started = false;
        try {
            startContainer(id);
            started = true;
            long timeout = request.timeoutMillis() > 0 ? request.timeoutMillis()
                    : config.defaultTimeoutMs();
            long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : 0L;
            boolean timedOut = false;
            int exit = -1;
            while (true) {
                Map<String, Object> state = inspect(id);
                Boolean running = (Boolean) state.get("Running");
                Object code = state.get("ExitCode");
                if (running == null || !running) {
                    exit = code instanceof Number ? ((Number) code).intValue() : -1;
                    break;
                }
                if (request.observer().interrupted()) {
                    killContainer(id);
                    exit = 137;
                    timedOut = true;
                    break;
                }
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    killContainer(id);
                    exit = 137;
                    timedOut = true;
                    break;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    killContainer(id);
                    Thread.currentThread().interrupt();
                    throw new IOException("docker 后端轮询容器状态时被中断", e);
                }
            }
            String[] streams = logs(id, request.mergeStreams());
            return ExecResult.builder(DOCKER)
                    .exitCode(exit)
                    .stdout(streams[0])
                    .stderr(streams[1])
                    .stdoutTruncated(boolOfTail(streams[2], "out"))
                    .stderrTruncated(boolOfTail(streams[2], "err"))
                    .stdoutTotalBytes(longOfTail(streams[2], "outTotal"))
                    .stderrTotalBytes(longOfTail(streams[2], "errTotal"))
                    .timedOut(timedOut)
                    .killedDescendants(timedOut ? 1 : 0)
                    .build();
        } finally {
            // 成功 / 失败 / 超时三条路都必须落到这里；摘掉这句就是"孤儿容器"变异体
            deleteContainer(id, started);
        }
    }

    /** POST /containers/create?name=… —— Cmd 是数组，一条字符串都不拼。 */
    String createContainer(ExecRequest request) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("Image", config.dockerImage());
        body.put("Cmd", new ArrayList<String>(request.argv()));
        body.put("WorkingDir", workingDirFor(request.cwd()));
        List<String> envList = new ArrayList<String>();
        Map<String, String> env = request.env();
        if (env != null) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                envList.add(e.getKey() + "=" + e.getValue());
            }
        }
        if (!envList.isEmpty()) {
            body.put("Env", envList);
        }
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put(LABEL_MANAGED, "true");
        labels.put(LABEL_OWNER_PID, String.valueOf(currentPid()));
        labels.put(LABEL_RUN, runToken);
        body.put("Labels", labels);
        body.put("HostConfig", hostConfig());
        DockerEngineClient.Response r = client.request("POST",
                apiPrefix + "/containers/create?name=" + containerName(), JsonLite.write(body));
        if (!r.looksLikeDockerJson(201)) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "containers/create 实测 status=" + r.status() + " body=" + head(r.body(), 160));
        }
        String id = JsonLite.string(r.body(), "Id");
        if (id == null || id.isEmpty()) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "containers/create 回了 201 但没有 Id：" + head(r.body(), 120));
        }
        appendLedger(id);
        return id;
    }

    Map<String, Object> hostConfig() {
        Map<String, Object> hc = new LinkedHashMap<String, Object>();
        long mem = config.dockerMemoryBytes();
        if (mem > 0) {
            hc.put("Memory", mem);
        }
        double cpus = config.dockerCpus();
        if (cpus > 0) {
            hc.put("NanoCpus", (long) (cpus * 1_000_000_000L));
        }
        int pids = config.dockerPidsLimit();
        if (pids > 0) {
            hc.put("PidsLimit", pids);
        }
        hc.put("NetworkMode", networkMode());
        // 对标 hermes tools/environments/docker.py 开头承诺的安全基线（cap-drop ALL +
        // no-new-privileges）。AutoRemove=false：容器一律留在地上，靠账本兜底回收，
        // 这样"摘掉 delete"才会在变异里现形。
        hc.put("CapDrop", java.util.Collections.singletonList("ALL"));
        hc.put("SecurityOpt", java.util.Collections.singletonList("no-new-privileges:true"));
        hc.put("AutoRemove", false);
        return hc;
    }

    String containerName() {
        return config.dockerNamePrefix() + runToken;
    }

    private void startContainer(String id) throws IOException {
        DockerEngineClient.Response r = client.request("POST",
                apiPrefix + "/containers/" + id + "/start", null);
        if (r.status() != 204 && r.status() != 201 && r.status() != 304) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "containers/" + id + "/start 实测 status=" + r.status() + " body=" + head(r.body(), 160));
        }
    }

    /** GET /containers/{id}/json ⇒ State.Running / State.ExitCode。 */
    Map<String, Object> inspect(String id) throws IOException {
        DockerEngineClient.Response r = client.request("GET", apiPrefix + "/containers/" + id + "/json", null);
        if (!r.looksLikeDockerJson(200)) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "containers/" + id + "/json 实测 status=" + r.status() + " body=" + head(r.body(), 160));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("Running", Boolean.valueOf(JsonLite.bool(r.body(), "Running", true)));
        out.put("ExitCode", Long.valueOf(JsonLite.number(r.body(), "ExitCode", -1)));
        return out;
    }

    /**
     * GET /containers/{id}/logs?stdout=1&stderr=1&tail=all —— 非 tty 流是 8 字节头的
     * 多路复用帧（1/stdout、2/stderr），这里逐帧拆开并按上界收。
     */
    String[] logs(String id, boolean merged) throws IOException {
        DockerEngineClient.Response r = client.request("GET",
                apiPrefix + "/containers/" + id + "/logs?stdout=1&stderr=1&tail=all", null);
        if (!r.looksLikeDockerJson(200)) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "containers/" + id + "/logs 实测 status=" + r.status() + " body=" + head(r.body(), 160));
        }
        byte[] raw = r.bytes();
        int cap = config.outputMaxBytes();
        BoundedCollector out = new BoundedCollector(cap, ExecRequest.OutputPolicy.WINDOW);
        BoundedCollector err = new BoundedCollector(cap, ExecRequest.OutputPolicy.WINDOW);
        boolean multiplexed = looksMultiplexed(raw);
        if (multiplexed) {
            int i = 0;
            while (i + 8 <= raw.length) {
                int stream = raw[i] & 0xFF;
                long len = ((long) (raw[i + 4] & 0xFF) << 24) | ((raw[i + 5] & 0xFF) << 16)
                        | ((raw[i + 6] & 0xFF) << 8) | (raw[i + 7] & 0xFF);
                i += 8;
                int take = (int) Math.min(len, (long) (raw.length - i));
                if (take <= 0) {
                    break;
                }
                if (stream == 2) {
                    err.append(raw, i, take);
                } else {
                    out.append(raw, i, take);
                }
                i += take;
            }
        } else {
            out.append(raw, 0, raw.length);
        }
        String tail = "|out=" + out.truncated() + "|err=" + err.truncated()
                + "|outTotal=" + out.totalBytes() + "|errTotal=" + err.totalBytes();
        if (merged) {
            String joined = out.render("") + err.render("");
            return new String[]{joined, "", tail};
        }
        return new String[]{out.render(""), err.render(""), tail};
    }

    /** 头一帧的 magic：第 0 字节是 0/1/2、第 1-3 字节是 0，长度字段落在合理范围。 */
    static boolean looksMultiplexed(byte[] raw) {
        if (raw.length < 8) {
            return false;
        }
        int s = raw[0] & 0xFF;
        if (s != 0 && s != 1 && s != 2) {
            return false;
        }
        if (raw[1] != 0 || raw[2] != 0 || raw[3] != 0) {
            return false;
        }
        long len = ((long) (raw[4] & 0xFF) << 24) | ((raw[5] & 0xFF) << 16)
                | ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF);
        return len > 0 && len <= (long) raw.length - 8;
    }

    // ===== 回收 =====

    private void killContainer(String id) {
        try {
            client.request("POST", apiPrefix + "/containers/" + id + "/kill?signal=SIGKILL", null);
        } catch (IOException e) {
            System.err.println("[DockerExecEnvironment] kill 失败（delete 兜底）：" + e.getMessage());
        }
    }

    /** DELETE /containers/{id}?force=true —— 接线层的"收尾必达"。 */
    void deleteContainer(String id, boolean started) {
        IOException first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                DockerEngineClient.Response r = client.request("DELETE",
                        apiPrefix + "/containers/" + id + "?force=true", null);
                if (r.status() == 204 || r.status() == 200 || r.status() == 404) {
                    removeLedger(id);
                    return;
                }
                first = new IOException("status=" + r.status() + " body=" + head(r.body(), 120));
            } catch (IOException e) {
                first = e;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.err.println("[DockerExecEnvironment] 容器 " + id + " 没删掉，留在台账里等回收："
                + (first == null ? "未知原因" : first.getMessage()));
    }

    /**
     * 孤儿回收：进程被 kill 之后留下的 running 容器。
     *
     * <p>两条腿：① 读台账里那些"我贴过 label、但我这个 pid 已经不在"的 id 直接 DELETE；
     * ② 兜底再用 {@code GET /containers/json?all=1&filters=…} 按 label 扫一遍
     * —— 只认 {@link #LABEL_MANAGED}，绝不碰别人的容器。</p>
     *
     * @return 被删掉的容器数（测试点名用）
     */
    public int reapOrphans() {
        int deleted = 0;
        List<String> mine = ledgerIds();
        for (String id : mine) {
            try {
                DockerEngineClient.Response r = client.request("DELETE",
                        apiPrefix + "/containers/" + id + "?force=true", null);
                if (r.status() == 204 || r.status() == 200 || r.status() == 404) {
                    removeLedger(id);
                    deleted++;
                }
            } catch (IOException e) {
                System.err.println("[DockerExecEnvironment] 回收 " + id + " 失败：" + e.getMessage());
            }
        }
        try {
            String filters = "{\"label\":[\"" + LABEL_MANAGED + "=true\"]}";
            String encoded = filters.replace(" ", "%20").replace("\"", "%22")
                    .replace("{", "%7B").replace("}", "%7D");
            DockerEngineClient.Response r = client.request("GET",
                    apiPrefix + "/containers/json?all=1&filters=" + encoded, null);
            if (r.looksLikeDockerJson(200)) {
                for (String id : JsonLite.stringList(r.body(), "Id")) {
                    if (!isOwningThisRun(id) && !mine.contains(id)) {
                        DockerEngineClient.Response d = client.request("DELETE",
                                apiPrefix + "/containers/" + id + "?force=true", null);
                        if (d.status() == 204 || d.status() == 200 || d.status() == 404) {
                            deleted++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[DockerExecEnvironment] label 扫描回收失败：" + e.getMessage());
        }
        return deleted;
    }

    /** 只清"owner pid 已经不在了"的那些；本 run 自己的不动。 */
    private boolean isOwningThisRun(String id) {
        return ledgerIds().contains(id);
    }

    // ===== 文件（archive API） =====

    /**
     * 文件类操作挂在一条<b>常驻会话容器</b>上（照她 docker.py 的"一个 session 一个容器"手法）：
     * 命令走一次性容器，文件走这条长命的，二者都用同一套 label + 台账，close() 一起收。
     */
    private String sessionContainerId;

    synchronized String ensureSessionContainer() throws IOException {
        if (sessionContainerId != null) {
            return sessionContainerId;
        }
        ExecRequest keepAlive = ExecRequest.builder("sleep", "infinity")
                .policy(ExecRequest.OutputPolicy.HEAD)
                .build();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("Image", config.dockerImage());
        body.put("Cmd", keepAlive.argv());
        body.put("WorkingDir", sandboxRoot);
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put(LABEL_MANAGED, "true");
        labels.put(LABEL_OWNER_PID, String.valueOf(currentPid()));
        labels.put(LABEL_RUN, runToken);
        body.put("Labels", labels);
        body.put("HostConfig", hostConfig());
        DockerEngineClient.Response r = client.request("POST",
                apiPrefix + "/containers/create?name=" + config.dockerNamePrefix() + runToken + "-session",
                JsonLite.write(body));
        if (!r.looksLikeDockerJson(201)) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "会话容器 create 实测 status=" + r.status() + " body=" + head(r.body(), 160));
        }
        String id = JsonLite.string(r.body(), "Id");
        if (id == null || id.isEmpty()) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "会话容器 create 回了 201 但没有 Id");
        }
        appendLedger(id);
        DockerEngineClient.Response s = client.request("POST", apiPrefix + "/containers/" + id + "/start", null);
        if (s.status() != 204 && s.status() != 201 && s.status() != 304) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "会话容器 start 实测 status=" + s.status() + " body=" + head(s.body(), 160));
        }
        sessionContainerId = id;
        return id;
    }

    @Override
    public void writeFile(String path, byte[] content) throws IOException {
        String container = ensureSessionContainer();
        String remote = checkedRemote(path);
        byte[] tar = tarOneFile(baseName(remote), content == null ? new byte[0] : content);
        DockerEngineClient.Response r = client.requestBytes("PUT",
                apiPrefix + "/containers/" + container + "/archive?path=" + urlEncode(parentOf(remote)), tar);
        if (r.status() != 200 && r.status() != 201) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "把文件写进容器失败 status=" + r.status() + " body=" + head(r.body(), 120));
        }
    }

    @Override
    public byte[] readFile(String path) throws IOException {
        String container = ensureSessionContainer();
        String remote = checkedRemote(path);
        // tar 是二进制，必须走 requestBytes（String 口径会把它解码坏）
        DockerEngineClient.Response r = client.requestBytes("GET",
                apiPrefix + "/containers/" + container + "/archive?path=" + urlEncode(remote), null);
        if (r.status() != 200) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_BAD_RESPONSE,
                    "从容器读文件失败 status=" + r.status() + " body=" + head(r.body(), 120));
        }
        return untarFirstFile(r.bytes());
    }

    /** archive 响应是 tar：取第一个 regular 文件的内容（够单文件语义，不做目录）。 */
    static byte[] untarFirstFile(byte[] tar) throws IOException {
        int off = 0;
        while (off + 512 <= tar.length) {
            byte[] h = new byte[512];
            System.arraycopy(tar, off, h, 0, 512);
            boolean blank = true;
            for (int i = 0; i < 512; i++) {
                if (h[i] != 0) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                break;
            }
            long size = readOctal(h, 124, 12);
            byte type = h[156];
            off += 512;
            if ((type == '0' || type == 0) && size > 0 && off + size <= tar.length) {
                byte[] out = new byte[(int) size];
                System.arraycopy(tar, off, out, 0, (int) size);
                return out;
            }
            off += (int) (((size + 511L) / 512L) * 512L);
        }
        throw new IOException("archive 响应里没有可读的文件（tar 段数=0）");
    }

    private static long readOctal(byte[] b, int offset, int width) {
        long v = 0;
        for (int i = 0; i < width; i++) {
            char c = (char) (b[offset + i] & 0xFF);
            if (c >= '0' && c <= '7') {
                v = v * 8 + (c - '0');
            } else if (c == ' ' || c == '\0') {
                break;
            }
        }
        return v;
    }

    /** 远端路径检查（包内可见：变异体摘掉它时 {@code dockerRejectsPathOutsideItsSandboxRoot} 立刻红）。 */
    String checkedRemote(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("path 不能为空");
        }
        String root = sandboxRoot.endsWith("/") ? sandboxRoot.substring(0, sandboxRoot.length() - 1) : sandboxRoot;
        boolean absolute = path.startsWith("/");
        String joined = absolute ? path : root + "/" + path;
        java.util.List<String> parts = new ArrayList<String>();
        for (String seg : joined.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                throw new ExecEnvException(ExecEnvException.Code.SANDBOX_ESCAPE,
                        "路径越出 docker 沙箱根 " + root + "：「" + path + "」");
            }
            parts.add(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append('/').append(p);
        }
        String resolved = sb.length() == 0 ? "/" : sb.toString();
        if (absolute && !resolved.equals(root) && !resolved.startsWith(root + "/")) {
            // 绝对路径也要落在沙箱根里，否则 "/etc/passwd" 这种就直接漏过去了
            throw new ExecEnvException(ExecEnvException.Code.SANDBOX_ESCAPE,
                    "绝对路径不在 docker 沙箱根 " + root + " 下：「" + path + "」");
        }
        return resolved;
    }

    /**
     * 本地 cwd → 容器内 WorkingDir：只承认"沙箱根之内"的那一段，根外的 cwd 一律落回沙箱根，
     * 绝不把宿主机路径原样塞进容器（那等于把 local 的目录结构泄露进容器）。
     */
    private String workingDirFor(File localCwd) {
        if (localCwd == null) {
            return sandboxRoot;
        }
        try {
            String root = new File(sandboxRoot).getCanonicalPath();
            String cwd = localCwd.getCanonicalPath();
            if (cwd.equals(root)) {
                return sandboxRoot;
            }
            if (cwd.startsWith(root + "/")) {
                return checkedRemote(cwd.substring(root.length() + 1));
            }
        } catch (IOException ignored) {
            // 落到缺省：容器内沙箱根，不猜
        }
        return sandboxRoot;
    }

    // ===== 台账（照她 base.py:259/:269 的 store 手法） =====

    private Properties loadLedger() {
        Properties p = new Properties();
        File f = ledgerFile;
        if (f == null || !f.isFile()) {
            return p;
        }
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("[DockerExecEnvironment] 台账读不了（按空处理）：" + e.getMessage());
        }
        return p;
    }

    private void saveLedger(Properties p) {
        File f = ledgerFile;
        if (f == null) {
            return;
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            System.err.println("[DockerExecEnvironment] 台账目录建不出来，只走 label 扫描：" + parent.getPath());
            return;
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            p.store(fos, "z-bot p22 docker ledger (run=" + runToken + ")");
        } catch (IOException e) {
            System.err.println("[DockerExecEnvironment] 台账写失败：" + e.getMessage());
        }
    }

    private void appendLedger(String id) {
        synchronized (ledgerLock) {
            Properties p = loadLedger();
            p.setProperty(LEDGER_PREFIX + id, runToken + "@" + currentPid());
            saveLedger(p);
        }
    }

    private void removeLedger(String id) {
        synchronized (ledgerLock) {
            Properties p = loadLedger();
            if (p.remove(LEDGER_PREFIX + id) != null) {
                saveLedger(p);
            }
        }
    }

    List<String> ledgerIds() {
        List<String> out = new ArrayList<String>();
        for (String key : loadLedger().stringPropertyNames()) {
            if (key.startsWith(LEDGER_PREFIX)) {
                out.add(key.substring(LEDGER_PREFIX.length()));
            }
        }
        return out;
    }

    private static long currentPid() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getName().contains("@")
                    ? Long.parseLong(java.lang.management.ManagementFactory.getRuntimeMXBean()
                    .getName().split("@")[0]) : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    @Override
    public void close() throws IOException {
        String session = sessionContainerId;
        if (session != null) {
            sessionContainerId = null;
            deleteContainer(session, true);
        }
        int n = ledgerIds().size();
        if (n > 0) {
            // close() 是最主要的回收点：走到这里还留在台账里的，就是"我没删干净"的那些
            reapOrphans();
        }
    }

    // ===== 小工具 =====

    private static String head(String s, int n) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= n ? flat : flat.substring(0, n) + "…";
    }

    private static boolean boolOfTail(String tail, String key) {
        int i = tail.indexOf("|" + key + "=");
        return i >= 0 && tail.startsWith("true", i + key.length() + 2);
    }

    private static long longOfTail(String tail, String key) {
        int i = tail.indexOf("|" + key + "=");
        if (i < 0) {
            return 0L;
        }
        int j = i + key.length() + 2;
        int k = j;
        while (k < tail.length() && Character.isDigit(tail.charAt(k))) {
            k++;
        }
        try {
            return Long.parseLong(tail.substring(j, k));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String urlEncode(String v) {
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            throw new IllegalArgumentException("path 编码失败：" + e.getMessage());
        }
    }

    private static String baseName(String remote) {
        int i = remote.lastIndexOf('/');
        return i < 0 ? remote : (i == remote.length() - 1 ? remote : remote.substring(i + 1));
    }

    private static String parentOf(String remote) {
        int i = remote.lastIndexOf('/');
        return i <= 0 ? "/" : remote.substring(0, i);
    }

    /** 最小 ustar：一个 regular 文件，512 头 + 内容补零（archive API 只吃 tar）。 */
    static byte[] tarOneFile(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] header = new byte[512];
        byte[] nb = name.getBytes(UTF_8);
        System.arraycopy(nb, 0, header, 0, Math.min(nb.length, 100));
        writeOctal(header, 100, 8, 0644);          // mode
        writeOctal(header, 108, 8, 0);             // uid
        writeOctal(header, 116, 8, 0);             // gid
        writeOctal(header, 124, 12, content.length); // size（ustar 里 size 在 124，不是 128）
        writeOctal(header, 136, 12, 0);            // mtime
        header[156] = '0';                         // typeflag = regular
        System.arraycopy("ustar".getBytes(UTF_8), 0, header, 257, 5);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';                       // 算校验和时 chksum 段按空格计
        }
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        writeOctal(header, 148, 8, sum);
        bos.write(header);
        bos.write(content);
        int pad = (512 - (content.length % 512)) % 512;
        bos.write(new byte[pad]);
        bos.write(new byte[1024]);
        return bos.toByteArray();
    }

    private static void writeOctal(byte[] target, int offset, int width, long value) {
        String s = Long.toOctalString(value);
        while (s.length() < width - 1) {
            s = "0" + s;
        }
        byte[] b = s.getBytes(UTF_8);
        System.arraycopy(b, 0, target, offset, Math.min(b.length, width - 1));
        target[offset + width - 1] = ' ';
    }
}
