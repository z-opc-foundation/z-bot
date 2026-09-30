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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安装期安全扫描 —— hermes {@code tools/skills_guard.py}（1153 行 / ~110 条 pattern）的
 * <b>规则子集</b>：pattern_id、severity、category 全部沿用她的命名，只做了 12 条正则 +
 * 1 条不可见字符 + 1 条结构性检查。做了哪几条 / 不做哪几条见
 * {@code _doc/005_testing/acceptance/p23/EVIDENCE.md} §4，两条表都是硬交付。
 *
 * <p>判定与她的 {@code VERDICT_INDEX}/{@code INSTALL_POLICY} 同构：
 * 出现 critical ⇒ {@code dangerous}；只有 high/medium ⇒ {@code caution}；否则 {@code safe}。
 * 来源信任级决定装不装：{@code bundled}（本机自带的技能源）放行 safe/caution、拦 dangerous；
 * {@code community}（外部目录/center 下发）caution 与 dangerous 都拦。</p>
 */
public final class SkillGuard {

    public static final String SCANNER_VERSION = "zbot-skills-guard-v1";

    /** 一条命中。 */
    public static final class Finding {
        public final String patternId;
        public final String severity;
        public final String category;
        public final String file;
        public final int line;
        public final String match;
        public final String description;

        Finding(String patternId, String severity, String category, String file,
                int line, String match, String description) {
            this.patternId = patternId;
            this.severity = severity;
            this.category = category;
            this.file = file;
            this.line = line;
            this.match = match;
            this.description = description;
        }

        @Override
        public String toString() {
            return severity + "/" + category + " " + patternId + " @ " + file + ":" + line
                    + " [" + abbrev(match) + "] — " + description;
        }
    }

    /** 一个技能的扫描结论。 */
    public static final class ScanResult {
        public final String skillName;
        public final String source;
        public final String verdict;
        public final List<Finding> findings;
        /** 按当前信任级该不该装。 */
        public final boolean blocked;
        public final String summary;

        ScanResult(String skillName, String source, String verdict,
                   List<Finding> findings, boolean blocked, String summary) {
            this.skillName = skillName;
            this.source = source;
            this.verdict = verdict;
            this.findings = findings;
            this.blocked = blocked;
            this.summary = summary;
        }
    }

    /** pattern_id → [regex, severity, category, description]；id 与 hermes 逐字同名。 */
    private static final Map<String, String[]> RULES = new LinkedHashMap<String, String[]>();

    static {
        add("env_exfil_curl", "curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)",
                "critical", "exfiltration", "curl 命令插值了密钥类环境变量");
        add("env_exfil_wget", "wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)",
                "critical", "exfiltration", "wget 命令插值了密钥类环境变量");
        add("ssh_dir_access", "(\\$HOME|~|\\$\\{HOME\\})/\\.ssh",
                "high", "exfiltration", "引用了用户 SSH 私钥目录");
        add("zbot_config_secret_read", "(\\.zbot/[\\w.-]*|zbot\\.home)[^\\n]*(config\\.properties|api\\.key)|minimax\\.api\\.key",
                "critical", "exfiltration", "读取 z-bot profile 里的 api key 配置");
        add("dump_all_env", "^(export -p|env\\s*\\|\\s*(grep|base64|curl|nc)|printenv\\s*\\|)",
                "high", "exfiltration", "整份导出环境变量");
        add("hardcoded_secret", "(sk-[A-Za-z0-9\\-_]{16,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{16,}|xox[baprs]-[A-Za-z0-9-]{10,})",
                "critical", "credential_exposure", "技能里硬编码了第三方凭证");
        add("embedded_private_key", "-----BEGIN [A-Z ]*PRIVATE KEY-----",
                "critical", "credential_exposure", "技能里内嵌了私钥");
        add("prompt_injection_ignore",
                "(ignore (all |any )?(previous|prior|above) (instructions|rules))|(忽略[以之上前]前的?(所有)?指令)|(disregard (the )?(system )?instructions)",
                "critical", "injection", "提示词注入：要求忽略既有指令");
        add("sys_prompt_override",
                "(system prompt[^\\n]{0,40}(override|replace|overwrite|new|print|reveal))|(reveal[\\w ]{0,20}system prompt)",
                "critical", "injection", "提示词注入：要求覆盖/泄露 system prompt");
        add("leak_system_prompt", "(repeat|print|output|leak)[\\w ]{0,20}(system prompt|隐藏指令|系统提示词)",
                "high", "injection", "提示词注入：要求泄露系统提示词");
        add("deception_hide", "(do not (tell|inform|mention) the user|不要告诉用户|hide (this|that) from the user)",
                "critical", "injection", "提示词注入：要求对用户隐瞒");
        add("translate_execute", "(translate[d]? (this|the) (text|above)[^\\n]{0,40}(run|execute|eval))",
                "critical", "injection", "提示词注入：翻译后立即执行的规避手法");
        add("destructive_root_rm", "rm\\s+(-[rf]+\\s+){1,2}/(\\s|$)",
                "critical", "destructive", "rm -rf 根目录");
        add("destructive_home_rm", "rm\\s+-[rf]{1,2}[^\\n]{0,20}(\\$HOME|~)(/|\\s|$)",
                "critical", "destructive", "rm -rf 家目录");
        add("truncate_system", ">\\s*/etc/(passwd|shadow|sudoers)",
                "critical", "destructive", "覆写系统账户文件");
        add("reverse_shell", "((nc|netcat)[^\\n]{0,30}-e\\s|/dev/tcp/[0-9]|bash -i >&)",
                "critical", "network", "反弹 shell");
        add("tunnel_service", "\\b(ngrok|serveo|localhost\\.run|cloudflared)\\b",
                "high", "network", "内网穿透/隧道服务");
        add("curl_pipe_shell", "(curl|wget)[^\\n]{0,120}\\|\\s*(sudo )?(ba|z|da)?sh",
                "critical", "supply_chain", "从远端管道直接执行脚本");
        add("echo_pipe_exec", "(echo|printf)[^\\n]{0,80}\\|\\s*(ba)?sh",
                "critical", "obfuscation", "管道执行拼接出来的脚本");
        add("base64_decode_pipe", "base64[^\\n]{0,40}(-d|--decode)[^\\n]{0,40}\\|",
                "high", "obfuscation", "base64 解码后管道执行");
        add("eval_string", "(^|[^\\w.])eval\\s*[(\"'\\[]|python[\\d.]*\\s+-c\\s+[\"']?(exec|__import__)",
                "high", "obfuscation", "对字符串求值/动态导入");
        add("path_traversal_deep", "(\\.\\./){3,}",
                "high", "traversal", "多层上跳的路径穿越");
        add("persistence_cron", "(crontab\\s+-|/etc/cron(\\.d|\\.)|\\b\\w+\\s+\\*\\s+\\*\\s+\\*\\s+\\*)",
                "medium", "persistence", "写定时任务做持久化");
        add("shell_rc_mod", ">>?\\s*(\\$HOME|~)/\\.(zshrc|bashrc|profile|zprofile)",
                "medium", "persistence", "改写 shell 启动文件");
        add("sudo_usage", "(^|\\s)sudo(\\s|$)",
                "high", "privilege_escalation", "要求提权执行");
        add("crypto_mining", "\\b(xmrig|monero|coinhive|cryptonight)\\b",
                "critical", "mining", "挖矿相关");
    }

    private static void add(String id, String regex, String severity, String category, String desc) {
        RULES.put(id, new String[]{regex, severity, category, desc});
    }

    /** 已实现的规则名（单测与 EVIDENCE 都按这份清单对账）。 */
    public static List<String> implementedRules() {
        List<String> out = new ArrayList<String>(RULES.keySet());
        out.add("invisible_unicode");
        out.add("structural_limits");
        Collections.sort(out);
        return out;
    }

    private static final Pattern[] COMPILED = compile();
    private static final String[] INVISIBLE = {"​", "‌", "‍", "⁠", "‪", "‫", "﻿"};

    private static Pattern[] compile() {
        Pattern[] out = new Pattern[RULES.size()];
        int i = 0;
        for (String[] r : RULES.values()) {
            out[i++] = Pattern.compile(r[0], Pattern.CASE_INSENSITIVE);
        }
        return out;
    }

    public static final int MAX_FILE_COUNT = 50;
    public static final long MAX_SINGLE_FILE_KB = 256;
    private static final List<String> SCANNABLE = Collections.unmodifiableList(Arrays.asList(
            ".md", ".txt", ".sh", ".bash", ".zsh", ".py", ".js", ".ts", ".json", ".yaml",
            ".yml", ".toml", ".rb", ".java", ".html", ".xml", ".env", ".ps1", ".pl"));

    private SkillGuard() {
    }

    /** 扫一个已落盘的技能目录。 */
    public static ScanResult scanSkill(File skillDir, String source) {
        String name = skillDir == null ? "?" : skillDir.getName();
        List<Finding> findings = new ArrayList<Finding>();
        File[] files = skillDir == null ? null : skillDir.listFiles();
        List<File> all = new ArrayList<File>();
        collect(skillDir, all, 0);
        int count = all.size();
        for (File f : all) {
            String rel = relativize(skillDir, f);
            if (!isScannable(f.getName())) {
                continue;
            }
            long kb = f.length() / 1024;
            if (kb > MAX_SINGLE_FILE_KB) {
                findings.add(new Finding("structural_limits", "medium", "structural",
                        rel, 0, f.length() + " bytes", "单个文件远超技能应有的体积"));
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            for (int idx = 0; idx < lines.size(); idx++) {
                String line = lines.get(idx);
                String[] ids = RULES.keySet().toArray(new String[0]);
                for (String id : ids) {
                    int slot = indexOf(id);
                    if (slot >= 0) {
                        matchOne(COMPILED[slot], id, line, rel, idx + 1, findings);
                    }
                }
                // 不可见 unicode（hermes 同款：一行只记一次）
                for (String ch : INVISIBLE) {
                    if (line.contains(ch)) {
                        findings.add(new Finding("invisible_unicode", "high", "injection",
                                rel, idx + 1, "U+" + Integer.toHexString(
                                        Character.codePointAt(ch, 0)).toUpperCase(Locale.ROOT),
                                "不可见 unicode 字符（可能用于藏文本）"));
                        break;
                    }
                }
            }
        }
        if (count > MAX_FILE_COUNT) {
            findings.add(new Finding("structural_limits", "medium", "structural",
                    skillDir == null ? "?" : skillDir.getPath(), 0, count + " files",
                    "技能目录文件数异常（上限 " + MAX_FILE_COUNT + "）"));
        }
        return verdict(name, source, findings);
    }

    /** 只扫一段文本（单条规则的单测入口）。 */
    public static List<Finding> scanText(String patternId, String text) {
        List<Finding> out = new ArrayList<Finding>();
        if (!RULES.containsKey(patternId)) {
            throw new IllegalArgumentException("未实现的规则: " + patternId);
        }
        matchOne(COMPILED[indexOf(patternId)], patternId, text, "inline", 1, out);
        return out;
    }

    private static void matchOne(Pattern p, String id, String line, String file, int ln,
                                 List<Finding> out) {
        Matcher m = p.matcher(line);
        if (m.find()) {
            String[] r = RULES.get(id);
            out.add(new Finding(id, r[1], r[2], file, ln, m.group(), r[3]));
        }
    }

    private static int indexOf(String id) {
        int i = 0;
        for (String k : RULES.keySet()) {
            if (k.equals(id)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static ScanResult verdict(String name, String source, List<Finding> findings) {
        boolean critical = false;
        for (Finding f : findings) {
            if ("critical".equals(f.severity)) {
                critical = true;
            }
        }
        String verdict = findings.isEmpty() ? "safe" : (critical ? "dangerous" : "caution");
        boolean blocked;
        String trust = source == null ? "community" : source.toLowerCase(Locale.ROOT);
        if ("bundled".equals(trust) || "local".equals(trust)) {
            blocked = "dangerous".equals(verdict);
        } else {
            blocked = !"safe".equals(verdict);
        }
        String summary = findings.isEmpty()
                ? "verdict=safe blocked=" + blocked
                : "verdict=" + verdict + " blocked=" + blocked + " " + findings.size() + " 条命中: "
                        + firstIds(findings);
        return new ScanResult(name, trust, verdict, findings, blocked, summary);
    }

    private static String firstIds(List<Finding> findings) {
        List<String> ids = new ArrayList<String>();
        for (Finding f : findings) {
            if (!ids.contains(f.patternId)) {
                ids.add(f.patternId);
            }
            if (ids.size() >= 6) {
                break;
            }
        }
        return String.join(",", ids);
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (dir == null || depth > 6) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                collect(k, out, depth + 1);
            } else if (k.isFile()) {
                out.add(k);
            }
        }
    }

    private static boolean isScannable(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : SCANNABLE) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String relativize(File base, File f) {
        String b = base == null ? "" : base.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.startsWith(b) ? p.substring(Math.min(b.length() + 1, p.length())) : p;
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 48 ? s : s.substring(0, 48) + "…";
    }
}
