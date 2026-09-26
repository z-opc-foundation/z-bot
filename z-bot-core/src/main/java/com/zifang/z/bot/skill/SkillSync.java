package com.zifang.z.bot.skill;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 技能播种/更新（对齐 hermes {@code tools/skills_sync.py} 的 manifest + origin_hash 语义）。
 *
 * <p>清单落在 {@code <skillsRoot>/.bundled_manifest}，每行 {@code 技能名:origin_hash}，
 * {@code origin_hash} 是<b>上一次同步落盘那一刻</b>目标目录的内容指纹（v1 老清单没有 hash，
 * 下次同步时以用户当前副本补基线）。四条规则一条都不能少：</p>
 * <ul>
 *   <li>新技能（清单里没有）⇒ 拷进去并记 origin_hash；同名本地副本已存在 ⇒ <b>保留用户的</b>，
 *       只有当本地副本与来源逐字节相同才记基线（记了别的会把后续更新永久毒化）。</li>
 *   <li>清单里有、盘上也有 ⇒ 先比 {@code user_hash} 与 {@code origin_hash}：
 *       <b>不等 = 用户改过 ⇒ 跳过不覆盖</b>；相等才允许按来源更新，并把 origin_hash 推到新值。</li>
 *   <li>清单里有、盘上没了 ⇒ <b>用户删过 ⇒ 不复活</b>，清单条目保留。</li>
 *   <li>来源里已经没有了 ⇒ 清单条目清掉（否则会被误读成"用户删过"）。</li>
 * </ul>
 *
 * <p>每次落盘前跑 {@link SkillGuard}；被拦的技能<b>一个字都不写</b>，也不进清单，
 * 并在报告里单列 {@code suppressed}。</p>
 */
public final class SkillSync {

    public static final String MANIFEST_FILE_NAME = ".bundled_manifest";

    private SkillSync() {
    }

    /** 一次 sync 的台账。 */
    public static final class Report {
        public final List<String> copied = new ArrayList<String>();
        public final List<String> updated = new ArrayList<String>();
        public final List<String> userModified = new ArrayList<String>();
        public final List<String> userDeleted = new ArrayList<String>();
        public final List<String> keptLocal = new ArrayList<String>();
        public final List<String> cleaned = new ArrayList<String>();
        public final List<String> suppressed = new ArrayList<String>();
        public final List<String> warnings = new ArrayList<String>();
        public int skipped;

        public int written() {
            return copied.size() + updated.size();
        }

        /** 面向终端的一行台账。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("sync 台账: 新增 ").append(copied.size())
                    .append(" / 更新 ").append(updated.size())
                    .append(" / 跳过 ").append(skipped)
                    .append(" / 用户改过不覆盖 ").append(userModified.size())
                    .append(" / 用户删过不复活 ").append(userDeleted.size())
                    .append(" / 安全拦截 ").append(suppressed.size())
                    .append(" / 清理失效条目 ").append(cleaned.size());
            for (String w : warnings) {
                sb.append("\n  ! ").append(w);
            }
            return sb.toString();
        }
    }

    /**
     * 把 {@code bundledDir} 下的技能同步进 {@code skillsRoot}。
     *
     * @param source 信任级（{@code bundled}=自带源，{@code community}=外部源）—— 决定 guard 阈值
     */
    public static Report sync(File bundledDir, File skillsRoot, String source) {
        Report r = new Report();
        if (bundledDir == null || !bundledDir.isDirectory() || skillsRoot == null) {
            r.warnings.add("技能源目录不存在: " + bundledDir);
            return r;
        }
        Map<String, String> manifest = readManifest(skillsRoot);
        Map<String, File> bundled = bundledSkills(bundledDir);

        for (Map.Entry<String, File> e : bundled.entrySet()) {
            String name = e.getKey();
            File src = e.getValue();
            String bundledHash = dirHash(src);
            File dest = new File(skillsRoot, name);
            SkillGuard.ScanResult scan = SkillGuard.scanSkill(src, source);
            if (scan.blocked) {
                r.suppressed.add(name);
                r.warnings.add(name + ": 安全扫描 verdict=" + scan.verdict
                        + " 已拦截，未落盘（" + scan.summary + "）");
                continue;
            }
            if (!manifest.containsKey(name)) {
                if (dest.exists()) {
                    r.skipped++;
                    if (dirHash(dest).equals(bundledHash)) {
                        manifest.put(name, bundledHash);
                    } else {
                        r.keptLocal.add(name);
                        r.warnings.add(name + ": 本地已有同名技能，保留你的副本（要换成源版本请先删掉本地目录）");
                    }
                    continue;
                }
                if (dest.getParentFile() != null) {
                    dest.getParentFile().mkdirs();
                }
                try {
                    copyTree(src, dest);
                    manifest.put(name, dirHash(dest));
                    r.copied.add(name);
                    if (!"safe".equals(scan.verdict)) {
                        r.warnings.add(name + ": 已落盘，但扫描判为 " + scan.verdict + "（"
                                + scan.summary + "）");
                    }
                } catch (IOException ex) {
                    r.warnings.add(name + ": 拷贝失败 " + ex.getMessage());
                }
                continue;
            }

            if (!dest.exists()) {
                // 清单里有、盘上没了 = 用户删过 ⇒ 不复活，条目保留
                r.userDeleted.add(name);
                r.skipped++;
                continue;
            }

            String originHash = manifest.get(name);
            String userHash = dirHash(dest);
            if (originHash == null || originHash.isEmpty()) {
                manifest.put(name, userHash);
                r.skipped++;
                continue;
            }
            if (!userHash.equals(originHash)) {
                r.userModified.add(name);
                r.skipped++;
                continue;
            }
            if (!bundledHash.equals(originHash)) {
                File backup = new File(skillsRoot, name + ".bak");
                try {
                    deleteRecursively(backup);
                    if (!src.getCanonicalFile().equals(dest.getCanonicalFile())) {
                        deleteRecursively(dest);
                        copyTree(src, dest);
                        manifest.put(name, dirHash(dest));
                        r.updated.add(name);
                    }
                } catch (IOException ex) {
                    r.warnings.add(name + ": 更新失败 " + ex.getMessage());
                } finally {
                    deleteRecursively(backup);
                }
            } else {
                r.skipped++;
            }
        }

        // 来源里已经没有的条目 ⇒ 清掉
        List<String> stale = new ArrayList<String>();
        for (String name : manifest.keySet()) {
            if (!bundled.containsKey(name)) {
                stale.add(name);
            }
        }
        for (String name : stale) {
            manifest.remove(name);
            r.cleaned.add(name);
        }
        writeManifest(skillsRoot, manifest);
        return r;
    }

    /** 装一个技能目录（guard 拦下就一个字节都不写）。 */
    public static Report install(File srcSkillDir, File skillsRoot, String source) {
        Report r = new Report();
        File staged = new File(skillsRoot, ".staging-" + System.nanoTime());
        skillsRoot.mkdirs();
        try {
            staged.mkdirs();
            copyTree(srcSkillDir, new File(staged, srcSkillDir.getName()));
            File inPlace = new File(staged, srcSkillDir.getName());
            SkillGuard.ScanResult scan = SkillGuard.scanSkill(inPlace, source);
            if (scan.blocked) {
                r.suppressed.add(inPlace.getName());
                r.warnings.add(inPlace.getName() + ": 安装期安全扫描 verdict=" + scan.verdict
                        + "，已拦下并删除落地文件（" + scan.summary + "）");
                deleteRecursively(staged);
                return r;
            }
            File dest = new File(skillsRoot, srcSkillDir.getName());
            if (dest.exists()) {
                deleteRecursively(dest);
            }
            Files.createDirectories(dest.toPath());
            copyTree(inPlace, dest);
            deleteRecursively(staged);
            Map<String, String> manifest = readManifest(skillsRoot);
            manifest.put(dest.getName(), dirHash(dest));
            writeManifest(skillsRoot, manifest);
            r.copied.add(dest.getName());
            if (!"safe".equals(scan.verdict)) {
                r.warnings.add(dest.getName() + ": 已落盘，判为 " + scan.verdict + "（"
                        + scan.summary + "）");
            }
        } catch (IOException ex) {
            r.warnings.add("安装失败 " + ex.getMessage());
            deleteRecursively(staged);   // 失败也要把落地前的暂存清干净
        }
        return r;
    }

    // ─────────────────────────────────────────────────────────── manifest/hash

    /** 读清单：兼容 v1（光秃秃的名字 ⇒ hash 为空，下次同步补基线）。 */
    public static Map<String, String> readManifest(File skillsRoot) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        File f = new File(skillsRoot, MANIFEST_FILE_NAME);
        if (!f.isFile()) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                int colon = t.indexOf(':');
                if (colon >= 0) {
                    out.put(t.substring(0, colon).trim(), t.substring(colon + 1).trim());
                } else {
                    out.put(t, "");
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    public static void writeManifest(File skillsRoot, Map<String, String> manifest) {
        StringBuilder sb = new StringBuilder();
        List<String> names = new ArrayList<String>(manifest.keySet());
        Collections.sort(names);
        for (String n : names) {
            sb.append(n).append(':').append(manifest.get(n)).append('\n');
        }
        try {
            skillsRoot.mkdirs();
            Files.write(new File(skillsRoot, MANIFEST_FILE_NAME).toPath(),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 清单写不进去 ⇒ 下次 sync 会重判；不静默改语义
        }
    }

    /** 目录内容指纹：排序后的相对路径 + 字节（hermes {@code _dir_hash} 同款）。 */
    public static String dirHash(File dir) {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            return "";
        }
        List<File> files = new ArrayList<File>();
        collect(dir, files);
        Collections.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        Properties rel = new Properties();
        List<String> paths = new ArrayList<String>();
        for (File f : files) {
            String r = relativize(dir, f);
            paths.add(r);
            rel.setProperty(r, f.getAbsolutePath());
        }
        Collections.sort(paths);
        try {
            for (String r : paths) {
                File f = new File(rel.getProperty(r));
                md5.update(r.getBytes(StandardCharsets.UTF_8));
                md5.update(Files.readAllBytes(f.toPath()));
            }
        } catch (IOException e) {
            // 读不动就当指纹不同 —— 保守方向是"不覆盖"
        }
        return String.format("%032x", new BigInteger(1, md5.digest()));
    }

    // ─────────────────────────────────────────────────────────────── 文件系统

    /** 一级/两级目录形态下的技能源集合：目录名 → 目录（含 SKILL.md 的那个）。 */
    static Map<String, File> bundledSkills(File bundledDir) {
        Map<String, File> out = new LinkedHashMap<String, File>();
        File[] kids = bundledDir.listFiles();
        if (kids == null) {
            return out;
        }
        java.util.Arrays.sort(kids, (a, b) -> a.getName().compareTo(b.getName()));
        for (File k : kids) {
            if (!k.isDirectory() || k.getName().startsWith(".")) {
                continue;
            }
            if (new File(k, "SKILL.md").isFile()) {
                out.put(k.getName(), k);
                continue;
            }
            File[] grand = k.listFiles();
            if (grand == null) {
                continue;
            }
            java.util.Arrays.sort(grand, (a, b) -> a.getName().compareTo(b.getName()));
            for (File g : grand) {
                if (g.isDirectory() && new File(g, "SKILL.md").isFile()) {
                    out.put(g.getName(), g);
                }
            }
        }
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                collect(k, out);
            } else if (k.isFile()) {
                out.add(k);
            }
        }
    }

    private static String relativize(File base, File f) {
        String b = base.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.startsWith(b) ? p.substring(Math.min(b.length() + 1, p.length())) : p;
    }

    private static void copyTree(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new IOException("mkdirs 失败: " + dest);
            }
            File[] kids = src.listFiles();
            if (kids == null) {
                return;
            }
            for (File k : kids) {
                copyTree(k, new File(dest, k.getName()));
            }
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Files.copy(src.toPath(), dest.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }
}
