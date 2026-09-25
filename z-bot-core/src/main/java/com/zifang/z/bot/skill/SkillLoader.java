package com.zifang.z.bot.skill;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md 加载器：frontmatter(name/description/version/metadata) + 正文。
 *
 * <p>目录形态兼容两种：{@code <root>/<name>/SKILL.md} 与
 * {@code <root>/<category>/<name>/SKILL.md}（对齐 z-opc center 下发格式）。
 * frontmatter 里可选 {@code slash: /xxx}，供通道层把技能注册成斜杠命令。</p>
 */
public final class SkillLoader {

    private SkillLoader() {
    }

    /** 扫描 root 下的全部技能（两级），目录缺 SKILL.md 的跳过。 */
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
        String name = skillDir.getName();
        String description = "";
        String version = "";
        String slash = "";
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        String body = content;

        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end > 0) {
                String fm = content.substring(3, end);
                body = content.substring(content.indexOf('\n', end + 1) + 1);
                String metaKey = null;
                for (String line : fm.split("\n")) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    String trimmed = line.trim();
                    int colon = trimmed.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, colon).trim();
                    String value = trimmed.substring(colon + 1).trim();
                    if (line.startsWith(" ") && metaKey != null) {
                        metadata.put(metaKey + "." + key, value);
                        if ("slash".equals(key)) {
                            slash = value;
                        }
                    } else if ("name".equals(key)) {
                        name = value;
                        metaKey = null;
                    } else if ("description".equals(key)) {
                        description = value;
                        metaKey = null;
                    } else if ("version".equals(key)) {
                        version = value;
                        metaKey = null;
                    } else if ("metadata".equals(key)) {
                        metaKey = value.isEmpty() ? "meta" : value;
                    } else {
                        metaKey = null;
                    }
                }
            }
        }
        return new Skill(name, description, version, slash, metadata,
                body.trim(), skillDir);
    }

    /** 一个已安装技能。 */
    public static final class Skill {
        public final String name;
        public final String description;
        public final String version;
        public final String slash;
        public final Map<String, String> metadata;
        public final String body;
        public final File dir;

        Skill(String name, String description, String version, String slash,
              Map<String, String> metadata, String body, File dir) {
            this.name = name;
            this.description = description;
            this.version = version;
            this.slash = slash;
            this.metadata = metadata;
            this.body = body;
            this.dir = dir;
        }
    }
}
