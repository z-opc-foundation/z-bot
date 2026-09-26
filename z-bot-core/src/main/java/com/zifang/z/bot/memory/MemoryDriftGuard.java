package com.zifang.z.bot.memory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 漂移取证与还原 —— hermes {@code tools/memory_tool.py} 两段的等价物：
 * {@code _detect_external_drift()}（:714，判"盘上内容不往返"并落 {@code .bak.<ts>} 快照）
 * 与 {@code _drift_error()}（:93，把 {@code bak_path} 报给人）。
 *
 * <p>要挡的事故只有一个：<b>本层把不是自己写的内容当成可覆盖的旧内容</b>。
 * MEMORY.md 是一个可以被别的通道改的文件（人用编辑器打开改两行、shell 里
 * {@code echo >> MEMORY.md}、并发的另一个 z-bot 进程、别的工具直接 write）。
 * 我们的写入是"整页替换"式的（条目清单渲染成整份文件），一旦判错就是把别人的字节删了。
 * 三条漂移信号：</p>
 * <ol>
 *   <li><b>不往返</b> — 解析再渲染得到的字节与盘上字节（去掉首尾空白后）不同；</li>
 *   <li><b>单条超预算</b> — 某一行比整份的字符预算还长：本层永远逐条写入，
 *       出现这种行只能是外部把自由文本塞进了"本层会当成一条"的位置；</li>
 *   <li><b>形状不是条目</b> — 行首不是 {@code - [时间戳] }：手编/追加的散文。</li>
 * </ol>
 *
 * <p>命中任一信号 ⇒ ①先把整份原字节快照到 {@code <file>.bak.<毫秒>}，
 * ②拒绝这次写入，③把快照路径写进异常。快照名带毫秒且<b>重名必加序号另开一枚</b>
 * （她按秒取整，同一秒内两次漂移会把第一枚证据覆盖掉，我们不抄这一处）。</p>
 */
public final class MemoryDriftGuard {

    /** 判定为漂移的结果：为什么 + 取证快照在哪。 */
    public static final class Drift {
        private final String signal;
        private final String detail;
        private final File backup;

        Drift(String signal, String detail, File backup) {
            this.signal = signal;
            this.detail = detail;
            this.backup = backup;
        }

        public String signal() {
            return signal;
        }

        public String detail() {
            return detail;
        }

        /** 盘上取证快照；备份写失败时为" intended 路径 + 失败说明"里的路径部分。 */
        public File backup() {
            return backup;
        }

        public boolean backedUp() {
            return backup != null && backup.isFile();
        }

        /** 给人看的完整说明（含补救通道指路）。 */
        public String message(String fileName) {
            String where = backup == null ? "（备份失败，盘上原样未动）" : backup.getAbsolutePath();
            return "拒绝写入 " + fileName + "：盘上内容不能经由本层解析器往返（信号 " + signal + "："
                    + detail + "）。这多半是补丁工具、shell 追加、手工编辑或另一个会话写的，"
                    + "照现在的内容整页刷出去会静默丢掉它们。已快照到 " + where
                    + "。请先处置漂移：把额外内容并入条目，或用 rewritePage 把整份重写成干净的条目清单，再重试。";
        }
    }

    /** 条目行的合法前缀：{@code - [} 起头，后面必须跟一个 {@code ] }。 */
    static final String ENTRY_PREFIX = "- [";

    private MemoryDriftGuard() {
    }

    // ===== 解析 / 渲染（往返的两端）=====

    /** 盘上字节 → 行清单（去空白行、逐行 trim；<b>不</b>过滤非条目行，让调用方看得见漂移）。 */
    public static List<String> parse(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 行清单 → 盘上字节（一行一条，无尾换行 —— 与历史落盘字节一致）。 */
    public static String render(List<String> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(entries.get(i));
        }
        return sb.toString();
    }

    /** 这条行是不是本层写得出来的条目（{@code - [时间戳] 正文}）。 */
    public static boolean isEntryLine(String line) {
        if (line == null || !line.startsWith(ENTRY_PREFIX)) {
            return false;
        }
        int close = line.indexOf(']', ENTRY_PREFIX.length());
        return close > ENTRY_PREFIX.length() && line.length() > close + 1
                && line.charAt(close + 1) == ' ';
    }

    /** 条目正文（去掉 {@code - [时间戳] } 前缀）；非条目行原样返回。 */
    public static String entryBody(String line) {
        if (!isEntryLine(line)) {
            return line;
        }
        return line.substring(line.indexOf(']', ENTRY_PREFIX.length()) + 2).trim();
    }

    /** 造一条本层形状的条目行。 */
    public static String entryLine(String timestamp, String body) {
        return ENTRY_PREFIX + timestamp + "] " + body;
    }

    // ===== 漂移判定 =====

    /**
     * 判一份文件有没有被外部改过。命中即先落 {@code .bak} 快照再返回，
     * <b>不改</b>被检测文件的一个字节。
     *
     * @return 漂移 ⇒ {@link Drift}；干净 ⇒ {@code null}（含文件不存在、内容为空白）
     */
    public static Drift detect(File target, MemorySection section) {
        if (section == null || !section.isEntryList() || !target.isFile()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;  // 读不动就别假称漂移
        }
        if (raw.trim().isEmpty()) {
            return null;
        }
        List<String> lines = parse(raw);
        String signal = null;
        String detail = null;

        if (!render(lines).equals(raw.trim())) {
            signal = "round-trip";
            detail = "解析再渲染得到的字节与盘上不一致";
        }
        if (signal == null) {
            for (String line : lines) {
                if (!isEntryLine(line)) {
                    signal = "shape";
                    detail = "存在非条目行 \"" + preview(line) + "\"";
                    break;
                }
            }
        }
        if (signal == null) {
            for (String line : lines) {
                if (line.length() > section.charLimit()) {
                    signal = "oversize";
                    detail = "单行 " + line.length() + " 字符 > 整份预算 " + section.charLimit();
                    break;
                }
            }
        }
        if (signal == null) {
            return null;
        }
        File bak = null;
        try {
            bak = backupFileFor(target.getParentFile(), target.getName(), System.currentTimeMillis());
            Files.copy(target.toPath(), bak.toPath());
        } catch (IOException e) {
            bak = new File(target.getParentFile(), target.getName() + ".bak." + System.currentTimeMillis());
        }
        return new Drift(signal, detail, bak);
    }

    /**
     * 这一枚快照该落在哪个文件名上：{@code <name>.bak.<毫秒>}，
     * 同一毫秒内已存在 ⇒ 依次试 {@code .bak.<毫秒>.1 .2 …}（同一秒两次漂移必须留两枚证据）。
     */
    static File backupFileFor(File dir, String name, long timestampMillis) {
        String base = name + ".bak." + timestampMillis;
        File f = new File(dir, base);
        int n = 1;
        while (f.exists()) {
            f = new File(dir, base + "." + n);
            n++;
        }
        return f;
    }

    // ===== 快照 / 还原 / 回读对账 =====

    /**
     * 写之前先把现有字节原样留一份。目标不存在 ⇒ null（没有可保的东西）。
     * 快照是<b>逐字节</b> {@code Files.copy}，不是"解析后重写"。
     */
    public static File snapshot(File target) throws IOException {
        if (!target.isFile()) {
            return null;
        }
        File bak = backupFileFor(target.getParentFile(), target.getName(),
                System.currentTimeMillis());
        Files.copy(target.toPath(), bak.toPath());
        return bak;
    }

    /**
     * 从快照把目标还原成<b>写入前的原字节</b>：临时文件 + 原子改名，
     * 还原后再回读一次与快照逐字节对账，对不上就是还原没成 —— 大声抛，不装成功。
     */
    public static void restore(File target, File backup) throws IOException {
        byte[] original = Files.readAllBytes(backup.toPath());
        atomicReplace(target, original);
        if (!java.util.Arrays.equals(Files.readAllBytes(target.toPath()), original)) {
            throw new IOException("还原后回读与原字节不一致：" + target.getAbsolutePath()
                    + " ← " + backup.getAbsolutePath());
        }
    }

    /** 临时文件 + {@code ATOMIC_MOVE}：读者只会看到"旧整份"或"新整份"，看不到半份。 */
    public static void atomicReplace(File target, byte[] payload) throws IOException {
        File dir = target.getParentFile();
        File tmp = new File(dir, target.getName() + ".tmp");
        try {
            Files.write(tmp.toPath(), payload);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            deleteQuietly(tmp);
        }
    }

    public static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            try {
                Files.deleteIfExists(f.toPath());
            } catch (IOException ignored) {
                // 半写残留清不掉不该把主失败原因盖掉
            }
        }
    }

    private static String preview(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
