package com.zifang.z.bot.skill;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SKILL.md 加载器：frontmatter(name/description/version/platforms/environments/prerequisites/metadata) + 正文。
 *
 * <p>目录形态兼容两种：{@code <root>/<name>/SKILL.md} 与
 * {@code <root>/<category>/<name>/SKILL.md}（对齐 z-opc center 下发格式）。</p>
 *
 * <p>frontmatter 里可选 {@code slash: /xxx}；没写就按技能名 slug 化生成。
 * 这份字段<b>有真实消费者</b>：{@link SkillCommands} 把 eligible 的技能编译成斜杠命令，
 * 由 {@code SlashRegistry.registerSkillCommands(...)} 注册进终端与 HTTP 共用的那一张命令表。</p>
 *
 * <p>门控语义对齐 hermes {@code agent/skill_utils.py}：</p>
 * <ul>
 *   <li>{@code platforms: [macos, linux]} —— OS 硬兼容门。不匹配 ⇒ 既不进命令表也不进 prompt，
 *       并给出可读理由 {@link Skill#hiddenReason}；缺省 = 全平台可用。</li>
 *   <li>{@code environments: [docker]} —— 相关性门（只在 offer 面生效）。当前环境不相关 ⇒ 隐藏；
 *       显式 {@code /skill <name>} 加载绕过此门。未知 env 标签 fail-open，不因为看不懂就藏掉技能。</li>
 *   <li>{@code prerequisites: {env_vars: [...], commands: [...]}} —— <b>不</b>用来丢技能。
 *       缺 env_var 时照 hermes {@code skill_commands.py:260} 的做法：继续加载，
 *       但带上可读的降级说明 {@link Skill#setupNote}；缺 commands 只是建议性的（advisory）。</li>
 * </ul>
 *
 * <p>量测口子（单测 / 真进程 E2E 用，不改生产判定）：{@code -Dzbot.skills.platform} 与
 * {@code -Dzbot.skills.environments}（逗号分隔）可覆盖自动探测。</p>
 */
public final class SkillLoader {

    private SkillLoader() {
    }

    /** hermes {@code skill_utils._KNOWN_ENVIRONMENTS} 同款：看得懂的 env 标签才参与判定。 */
    static final List<String> KNOWN_ENVIRONMENTS = Collections.unmodifiableList(
            Arrays.asList("kanban", "docker", "s6"));

    // ───────────────────────────────────────────────────────────── scan/parse

    /** 扫描 root 下的全部技能（两级），目录缺 SKILL.md 的跳过。<b>不做门控</b>，用于 view/统计。 */
    public static List<Skill> scan(File root) {
        List<Skill> out = new ArrayList<Skill>();
        File[] children = root == null ? null : root.listFiles();
        if (children == null) {
            return out;
        }
        for (File c : children) {
            if (!c.isDirectory()) {
                continue;
            }
            Skill s = tryParse(c);
            if (s != null) {
                out.add(s);
                continue;
            }
            File[] grand = c.listFiles();
            if (grand == null) {
                continue;
            }
            for (File g : grand) {
                if (g.isDirectory()) {
                    Skill gs = tryParse(g);
                    if (gs != null) {
                        out.add(gs);
                    }
                }
            }
        }
        out.sort((a, b) -> a.name.compareTo(b.name));
        return out;
    }

    /**
     * 当前该被"供货"的技能（命令表 + prompt 都用这一份）：platform/environment 门都过，
     * prerequisites 缺<b>不</b>剔除，只带降级说明。
     */
    public static List<Skill> scanOffers(File root) {
        List<Skill> out = new ArrayList<Skill>();
        for (Skill s : scan(root)) {
            if (s.hiddenReason == null) {
                out.add(s);
            }
        }
        return out;
    }

    /** 解析单个技能目录；无/坏 SKILL.md 返回 null。 */
    public static Skill tryParse(File skillDir) {
        File f = new File(skillDir, "SKILL.md");
        if (!f.isFile()) {
            return null;
        }
        try {
            return parse(skillDir, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** 解析 SKILL.md 文本。 */
    public static Skill parse(File skillDir, String content) {
        Map<String, Object> fm = new LinkedHashMap<String, Object>();
        String body = content;
        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end > 0) {
                fm = Frontmatter.parse(content.substring(3, end));
                body = content.substring(content.indexOf('\n', end + 1) + 1);
            }
        }

        String name = str(fm.get("name"));
        if (name.isEmpty()) {
            name = skillDir.getName();
        }
        String description = stripQuotes(str(fm.get("description")));
        String version = stripQuotes(str(fm.get("version")));

        // slash：顶层优先，其次 metadata.slash（历史写法），再次 metadata.hermes.slash
        String slash = stripQuotes(str(fm.get("slash")));
        Map<String, Object> metadata = asMap(fm.get("metadata"));
        if (slash.isEmpty()) {
            slash = stripQuotes(str(metadata.get("slash")));
        }
        Map<String, Object> hermes = asMap(metadata.get("hermes"));
        if (slash.isEmpty()) {
            slash = stripQuotes(str(hermes.get("slash")));
        }

        List<String> platforms = asStrings(fm.get("platforms"));
        if (platforms.isEmpty()) {
            platforms = asStrings(fm.get("platform"));   // agentskills.io 单数写法
        }
        List<String> environments = asStrings(fm.get("environments"));

        Map<String, Object> prereqs = asMap(fm.get("prerequisites"));
        List<String> requiredEnv = new ArrayList<String>(asStrings(prereqs.get("env_vars")));
        if (requiredEnv.isEmpty()) {
            requiredEnv.addAll(asStrings(prereqs.get("env")));   // 老写法
        }
        requiredEnv.addAll(asStrings(fm.get("required_environment_variables")));
        List<String> requiredCommands = new ArrayList<String>(asStrings(prereqs.get("commands")));

        // metadata 摊平成点号键；保留历史约定：metadata 的直接子标量 → "meta.<key>"，
        // 嵌套的那一层（hermes:）用自身名字作前缀 → "hermes.tags"
        Map<String, String> flat = new LinkedHashMap<String, String>();
        flattenMeta(metadata, flat);

        List<String> tags = new ArrayList<String>(asStrings(hermes.get("tags")));
        if (tags.isEmpty()) {
            tags.addAll(asStrings(metadata.get("tags")));
        }
        List<String> related = new ArrayList<String>(asStrings(hermes.get("related_skills")));
        if (related.isEmpty()) {
            related.addAll(asStrings(metadata.get("related_skills")));
        }

        String platform = currentPlatform();
        String hidden = null;
        if (!matchesPlatform(platforms, platform)) {
            hidden = "platforms=" + platforms + " 与当前平台 " + platform + " 不匹配";
        } else if (!matchesEnvironment(environments, activeEnvironments())) {
            hidden = "environments=" + environments + " 与当前运行环境 "
                    + activeEnvironments() + " 不相关";
        }

        List<String> missingEnv = new ArrayList<String>();
        for (String k : dedupe(requiredEnv)) {
            if (System.getenv(k) == null || System.getenv(k).trim().isEmpty()) {
                missingEnv.add(k);
            }
        }
        List<String> missingCommands = new ArrayList<String>();
        for (String c : dedupe(requiredCommands)) {
            if (!onPath(c)) {
                missingCommands.add(c);
            }
        }
        String setupNote = null;
        if (!missingEnv.isEmpty()) {
            setupNote = "Required environment setup was skipped: 缺少环境变量 "
                    + String.join(", ", missingEnv)
                    + " —— 技能继续加载，但相关功能会降级（先导出这些变量再重载可恢复）。";
        } else if (!missingCommands.isEmpty()) {
            setupNote = "advisory: 依赖命令未在 PATH 上: " + String.join(", ", missingCommands)
                    + " —— 仅提示，不影响加载。";
        }

        return new Skill(name, description, version, slash, flat, body.trim(), skillDir,
                platforms, environments, dedupe(requiredEnv), dedupe(requiredCommands),
                dedupe(tags), dedupe(related), missingEnv, missingCommands, setupNote, hidden);
    }

    // ────────────────────────────────────────────────────────── gating helpers

    /** 当前 OS 的逻辑名：macos / linux / windows / unknown。 */
    public static String currentPlatform() {
        String v = trim(System.getProperty("zbot.skills.platform"));
        if (v.isEmpty()) {
            v = trim(System.getenv("ZBOT_SKILL_PLATFORM"));
        }
        if (!v.isEmpty()) {
            return v.toLowerCase(Locale.ROOT);
        }
        String os = trim(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return "unknown";
    }

    /** 当前激活的运行环境（hermes 同款：探测不到就当没有；空 = 不在任何已知环境里）。 */
    public static List<String> activeEnvironments() {
        String v = trim(System.getProperty("zbot.skills.environments"));
        if (v.isEmpty()) {
            v = trim(System.getenv("ZBOT_SKILL_ENVIRONMENTS"));
        }
        if (!v.isEmpty()) {
            List<String> out = new ArrayList<String>();
            for (String part : v.split(",")) {
                String p = part.trim().toLowerCase(Locale.ROOT);
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
            return out;
        }
        List<String> out = new ArrayList<String>();
        if (new File("/.dockerenv").exists() || fileContains("/proc/1/cgroup", "docker")) {
            out.add("docker");
        }
        if (new File("/package/admin/s6").exists() || new File("/run/s6").exists()) {
            out.add("s6");
        }
        if (fileContains("/proc/1/cmdline", "kanban")) {
            out.add("kanban");
        }
        return out;
    }

    /** platforms 判定：空 = 全平台；任一项命中即通过（OR）。 */
    public static boolean matchesPlatform(List<String> platforms) {
        return matchesPlatform(platforms, currentPlatform());
    }

    public static boolean matchesPlatform(List<String> platforms, String current) {
        if (platforms == null || platforms.isEmpty()) {
            return true;
        }
        for (String p : platforms) {
            String norm = p == null ? "" : p.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) {
                continue;
            }
            if (norm.equals(current)) {
                return true;
            }
            // hermes 的 PLATFORM_MAP 里 termux/android 都落回 linux；本机不会有 android，保留 mac 别名
            if ("darwin".equals(norm) && "macos".equals(current)) {
                return true;
            }
            if ("android".equals(norm) && "linux".equals(current)) {
                return true;
            }
        }
        return false;
    }

    /** environments 判定：相关性门，OR 语义，未知标签 fail-open。 */
    public static boolean matchesEnvironment(List<String> environments) {
        return matchesEnvironment(environments, activeEnvironments());
    }

    public static boolean matchesEnvironment(List<String> environments, List<String> active) {
        if (environments == null || environments.isEmpty()) {
            return true;
        }
        for (String e : environments) {
            String norm = e == null ? "" : e.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) {
                continue;
            }
            if (!KNOWN_ENVIRONMENTS.contains(norm)) {
                return true;                     // 看不懂的标签不藏技能（hermes: fail open）
            }
            if (active != null && active.contains(norm)) {
                return true;
            }
        }
        return false;
    }

    /** prerequisites 缺失 ⇒ 不隐藏，只降级；这是和 platforms 的关键区别。 */
    public static boolean isDegraded(Skill s) {
        return s != null && !s.missingEnvVars.isEmpty();
    }

    private static void flattenMeta(Map<String, Object> src, Map<String, String> out) {
        flattenMeta(src, "", out);
    }

    private static void flattenMeta(Map<String, Object> src, String prefix, Map<String, String> out) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String rawKey = e.getKey();
            Object v = e.getValue();
            // 第一层：标量沿用历史的 "meta.<key>"；嵌套 map（hermes: 等）用自身名字当命名空间
            String key = prefix.isEmpty()
                    ? (v instanceof Map ? rawKey : "meta." + rawKey)
                    : prefix + "." + rawKey;
            if (v instanceof Map) {
                flattenMeta(asMap(v), key, out);
            } else if (v instanceof List) {
                out.put(key, String.join(",", asStrings(v)));
            } else {
                String s = str(v);
                // 行内列表 "tags: [qa, testing]" 摊平时归一化成 qa,testing
                out.put(key, s.startsWith("[") && s.endsWith("]")
                        ? String.join(",", asStrings(s)) : s);
            }
        }
    }

    private static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<String>();
        for (String s : in) {
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean onPath(String command) {
        if (command == null || command.trim().isEmpty()) {
            return true;
        }
        if (command.contains("/")) {
            return new File(command).isFile();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, command).isFile()) {
                return true;
            }
        }
        return false;
    }

    private static boolean fileContains(String path, String needle) {
        try {
            File f = new File(path);
            if (!f.isFile()) {
                return false;
            }
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains(needle);
        } catch (IOException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
    }

    private static List<String> asStrings(Object v) {
        List<String> out = new ArrayList<String>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                String s = str(o);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } else if (v instanceof String) {
            String s = stripQuotes(str(v));
            if (s.startsWith("[") && s.endsWith("]") && s.length() > 1) {
                for (String part : s.substring(1, s.length() - 1).split(",")) {
                    String p = stripQuotes(part.trim());
                    if (!p.isEmpty()) {
                        out.add(p);
                    }
                }
            } else if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String stripQuotes(String v) {
        if (v == null || v.length() < 2) {
            return v == null ? "" : v.trim();
        }
        String s = v.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }

    // ───────────────────────────────────────────────────────────── data class

    /** 一个已安装技能。 */
    public static final class Skill {
        public final String name;
        public final String description;
        public final String version;
        public final String slash;
        public final Map<String, String> metadata;
        public final String body;
        public final File dir;
        public final List<String> platforms;
        public final List<String> environments;
        public final List<String> requiredEnvVars;
        public final List<String> requiredCommands;
        public final List<String> tags;
        public final List<String> relatedSkills;
        public final List<String> missingEnvVars;
        public final List<String> missingCommands;
        /** 缺前置时的可读降级说明；前置齐 ⇒ null。 */
        public final String setupNote;
        /** 为什么没进命令表 / prompt；eligible ⇒ null。显式 {@code /skill} 加载绕过它。 */
        public final String hiddenReason;

        Skill(String name, String description, String version, String slash,
              Map<String, String> metadata, String body, File dir) {
            this(name, description, version, slash, metadata, body, dir,
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList(), null, null);
        }

        Skill(String name, String description, String version, String slash,
              Map<String, String> metadata, String body, File dir,
              List<String> platforms, List<String> environments,
              List<String> requiredEnvVars, List<String> requiredCommands,
              List<String> tags, List<String> relatedSkills,
              List<String> missingEnvVars, List<String> missingCommands,
              String setupNote, String hiddenReason) {
            this.name = name;
            this.description = description;
            this.version = version;
            this.slash = slash;
            this.metadata = metadata;
            this.body = body;
            this.dir = dir;
            this.platforms = platforms;
            this.environments = environments;
            this.requiredEnvVars = requiredEnvVars;
            this.requiredCommands = requiredCommands;
            this.tags = tags;
            this.relatedSkills = relatedSkills;
            this.missingEnvVars = missingEnvVars;
            this.missingCommands = missingCommands;
            this.setupNote = setupNote;
            this.hiddenReason = hiddenReason;
        }

        /** 进 prompt / 命令表的资格。 */
        public boolean offerable() {
            return hiddenReason == null;
        }
    }
}
