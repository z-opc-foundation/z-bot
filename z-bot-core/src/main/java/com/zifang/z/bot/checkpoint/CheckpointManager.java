package com.zifang.z.bot.checkpoint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 影子 git checkpoint：对 agent 沙箱目录做快照与回滚（对齐 hermes checkpoint_manager.py）。
 *
 * <p>git 仓库与工作区分离：仓库在 {@code storeDir/.git}，每条命令都用
 * {@code GIT_DIR} + {@code GIT_WORK_TREE} 指向沙箱 — 沙箱里不会冒出 .git 目录，
 * 相同内容由 git 对象库天然去重。每个快照额外挂
 * {@code refs/zbot/ckpt/<id>} 引用，列表/回滚/修剪全部基于这些引用，
 * 不依赖分支形态，也不受 gc 影响。</p>
 *
 * <p>回滚语义：把 index 指到目标提交（{@code read-tree}）、全量物化
 * （{@code checkout-index -a -f}）、再清掉快照后新增的文件（{@code clean -fd}），
 * 沙箱精确回到快照时刻。被 .gitignore 忽略的文件从未进过快照，回滚时保留原样。</p>
 */
public final class CheckpointManager {

    /** 快照 id 只接受 4-40 位十六进制（防 git 参数注入）。 */
    private static final java.util.regex.Pattern ID_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{4,40}$");

    private static final long GIT_TIMEOUT_SECONDS = 30;

    private final File storeDir;
    private final File gitDir;
    private final File workTree;
    private volatile boolean repoReady;

    public CheckpointManager(File storeDir, File workTree) {
        this.storeDir = storeDir;
        this.gitDir = new File(storeDir, ".git");
        this.workTree = workTree;
    }

    /** 本机是否有可用 git（没有则所有快照静默降级为 no-op）。 */
    public static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            drain(p.getInputStream());
            return p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 对当前沙箱状态打一个快照。
     *
     * @param reason 快照原因（一般是触发它的破坏性工具名）
     * @return 短 id（12 位十六进制），供 {@link #rollback} 使用
     * @throws IOException git 不可用或命令失败
     */
    public synchronized String snapshot(String reason) throws IOException {
        ensureRepo();
        run(storeDir, repoArgs("add", "-A"));
        String message = "checkpoint[" + reason + "] " + java.time.Instant.now();
        run(storeDir, repoArgs("commit", "--allow-empty", "-m", message));
        String sha = run(storeDir, repoArgs("rev-parse", "HEAD")).trim();
        String id = sha.length() > 12 ? sha.substring(0, 12) : sha;
        run(storeDir, repoArgs("update-ref", "refs/zbot/ckpt/" + id, sha));
        return id;
    }

    /** 列出全部快照，新的在前（按 HEAD 线性祖先序，同秒提交也能稳定排序）。 */
    public synchronized List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<Entry>();
        if (!gitDir.isDirectory()) {
            return out;
        }
        // for-each-ref 的 format 不支持 %xXX 转义（会按字面输出），分隔符必须用真实 tab 字符
        String fmt = "%(objectname)\t%(refname)\t%(creatordate:iso8601)\t%(contents:subject)";
        String stdout = run(storeDir, repoArgs("for-each-ref", "--format=" + fmt, "refs/zbot/ckpt"));
        Map<String, String[]> bySha = new LinkedHashMap<String, String[]>();
        for (String line : stdout.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 4) {
                continue;
            }
            bySha.put(parts[0].trim(), parts);
        }
        if (bySha.isEmpty()) {
            return out;
        }
        // 每次快照都是 HEAD 上的一次提交，rev-list 的输出天然从新到旧；
        // --sort=-creatordate 只有秒级精度，同秒快照顺序会抖，所以按祖先序排
        String revList = run(storeDir, repoArgs("rev-list", "HEAD"));
        for (String sha : revList.split("\n")) {
            String[] parts = bySha.remove(sha.trim());
            if (parts != null) {
                out.add(toEntry(parts));
            }
        }
        for (String[] parts : bySha.values()) {
            out.add(toEntry(parts));
        }
        return out;
    }

    private static Entry toEntry(String[] parts) {
        String sha = parts[0].trim();
        String id = sha.length() > 12 ? sha.substring(0, 12) : sha;
        return new Entry(id, parts[2].trim(), parts[3].trim());
    }

    /**
     * 把沙箱恢复到指定快照；id 为空取最近一次。
     *
     * @return 恢复到的快照 id
     * @throws IOException id 不存在或 git 命令失败
     */
    public synchronized String rollback(String id) throws IOException {
        String target = id == null || id.trim().isEmpty() ? null : id.trim().toLowerCase(Locale.ROOT);
        if (target == null) {
            List<Entry> entries = list();
            if (entries.isEmpty()) {
                throw new IOException("暂无可回滚的 checkpoint（沙箱还没被打过快照）");
            }
            target = entries.get(0).id;
        }
        if (!ID_PATTERN.matcher(target).matches()) {
            throw new IOException("非法 checkpoint id: " + id);
        }
        ensureRepo();
        String sha = run(storeDir, repoArgs("rev-parse", "--verify", target + "^{commit}")).trim();
        if (sha.isEmpty()) {
            throw new IOException("checkpoint 不存在: " + target);
        }
        // index 指向目标提交 → 全量物化文件 → 清掉快照后新增的文件
        run(storeDir, repoArgs("read-tree", sha));
        run(storeDir, repoArgs("checkout-index", "-a", "-f"));
        run(storeDir, repoArgs("clean", "-fd"));
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    /**
     * 只保留最近 {@code keep} 个快照，其余修剪掉。
     *
     * @return 被删除的快照数
     */
    public synchronized int prune(int keep) throws IOException {
        if (keep < 0) {
            throw new IOException("keep 不能为负数: " + keep);
        }
        List<Entry> entries = list();
        int deleted = 0;
        for (int i = keep; i < entries.size(); i++) {
            run(storeDir, repoArgs("update-ref", "-d", "refs/zbot/ckpt/" + entries.get(i).id));
            deleted++;
        }
        return deleted;
    }

    /** 丢弃一个刚打的快照（工具被审批拦下、实际没执行时清掉噪音）。 */
    public synchronized void discard(String id) {
        if (id == null || id.isEmpty() || !gitDir.isDirectory()) {
            return;
        }
        try {
            run(storeDir, repoArgs("update-ref", "-d", "refs/zbot/ckpt/" + id));
        } catch (IOException e) {
            // 丢不掉只是列表多一条噪音，不影响功能
        }
    }

    // ===== 内部 =====

    private void ensureRepo() throws IOException {
        if (repoReady && gitDir.isDirectory()) {
            return;
        }
        if (!workTree.isDirectory() && !workTree.mkdirs()) {
            throw new IOException("沙箱目录不可用: " + workTree);
        }
        if (!storeDir.isDirectory() && !storeDir.mkdirs()) {
            throw new IOException("checkpoint 仓库目录不可创建: " + storeDir);
        }
        if (!gitDir.isDirectory()) {
            // init 单独跑：不带 GIT_DIR/GIT_WORK_TREE，让 .git 落在 storeDir 下
            ProcessBuilder pb = new ProcessBuilder("git", "init");
            pb.directory(storeDir);
            pb.environment().put("HOME", storeDir.getAbsolutePath());
            pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            exec(pb, "git init");
        }
        repoReady = true;
    }

    /** 组装带仓库环境变量的 git 命令参数（cwd 一律用 storeDir，仓库定位全靠 env）。 */
    private String[] repoArgs(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return cmd;
    }

    private String run(File cwd, String[] cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.environment().put("GIT_DIR", gitDir.getAbsolutePath());
        pb.environment().put("GIT_WORK_TREE", workTree.getAbsolutePath());
        // 隔离用户级/系统级 gitconfig，快照行为只由命令行决定
        pb.environment().put("HOME", storeDir.getAbsolutePath());
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        pb.environment().put("GIT_AUTHOR_NAME", "z-bot");
        pb.environment().put("GIT_AUTHOR_EMAIL", "z-bot@localhost");
        pb.environment().put("GIT_COMMITTER_NAME", "z-bot");
        pb.environment().put("GIT_COMMITTER_EMAIL", "z-bot@localhost");
        return exec(pb, String.join(" ", cmd));
    }

    private static String exec(ProcessBuilder pb, String what) throws IOException {
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out = drain(p.getInputStream());
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(what + " 超时（>" + GIT_TIMEOUT_SECONDS + "s）");
            }
            if (p.exitValue() != 0) {
                throw new IOException(what + " 失败 (rc=" + p.exitValue() + "): " + out.trim());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(what + " 被中断");
        }
    }

    private static String drain(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 一条快照记录：短 id / 时间 / 触发原因。 */
    public static final class Entry {
        public final String id;
        public final String time;
        public final String subject;

        public Entry(String id, String time, String subject) {
            this.id = id;
            this.time = time;
            this.subject = subject;
        }
    }
}
