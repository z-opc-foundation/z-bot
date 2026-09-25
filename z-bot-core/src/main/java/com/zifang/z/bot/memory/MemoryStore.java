package com.zifang.z.bot.memory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/**
 * 本地长期记忆三层（对齐 hermes memories）：
 * <ul>
 *   <li>{@code MEMORY.md} — agent 主动积累的长期记忆；</li>
 *   <li>{@code USER.md} — 用户画像/偏好；</li>
 *   <li>{@code SOUL.md} — 人格文件，缺省生成、可手编，注入 system prompt 头部。</li>
 * </ul>
 *
 * <p>写入走 {@code <file>.lock} 文件锁 + 临时文件原子替换，多进程同写不撕裂。</p>
 */
public final class MemoryStore {

    private static final String DEFAULT_SOUL = "# SOUL\n\n"
            + "你是 z-bot，一个务实、直接的本地 Agent。\n"
            + "- 说人话，先给结论再给依据；\n"
            + "- 能用工具解决的绝不空谈，做完为止；\n"
            + "- 对不可逆操作保持谨慎，拿不准就先问。\n";

    private final File dir;

    public MemoryStore(File dir) {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("记忆目录不可创建: " + dir);
        }
    }

    public File getDir() {
        return dir;
    }

    // ===== 读 =====

    public String readMemory() {
        return readFile(new File(dir, "MEMORY.md"));
    }

    public String readUser() {
        return readFile(new File(dir, "USER.md"));
    }

    public String readSoul() {
        return readFile(new File(dir, "SOUL.md"));
    }

    public boolean isEmpty() {
        return readMemory().isEmpty() && readUser().isEmpty();
    }

    // ===== 写（append 直接执行；rewrite 语义上需审批，由调用方走 Confirmations 流程） =====

    /** 追加一条带时间戳的记忆行。 */
    public void appendMemory(String entry) throws IOException {
        append(new File(dir, "MEMORY.md"), entry);
    }

    public void appendUser(String entry) throws IOException {
        append(new File(dir, "USER.md"), entry);
    }

    /** 整页重写 MEMORY.md。 */
    public void rewriteMemory(String content) throws IOException {
        lockedWrite(new File(dir, "MEMORY.md"), content);
    }

    /** 整页重写 USER.md。 */
    public void rewriteUser(String content) throws IOException {
        lockedWrite(new File(dir, "USER.md"), content);
    }

    /** SOUL.md 不存在时生成默认人格；已存在则原样保留。 */
    public void ensureSoul() throws IOException {
        File f = new File(dir, "SOUL.md");
        if (!f.exists()) {
            lockedWrite(f, DEFAULT_SOUL);
        }
    }

    public void clearMemory() throws IOException {
        rewriteMemory("");
    }

    public void clearUser() throws IOException {
        rewriteUser("");
    }

    // ===== 内部 =====

    private static String readFile(File f) {
        if (!f.isFile()) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void append(File f, String entry) throws IOException {
        String existing = readFile(f);
        String line = "- [" + Instant.now() + "] " + entry.trim();
        String out = existing.isEmpty() ? line : existing + "\n" + line;
        lockedWrite(f, out);
    }

    private static void lockedWrite(File target, String content) throws IOException {
        File lock = new File(target.getParentFile(), target.getName() + ".lock");
        try (RandomAccessFile raf = new RandomAccessFile(lock, "rw");
             FileChannel ch = raf.getChannel();
             FileLock ignored = ch.lock()) {
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
