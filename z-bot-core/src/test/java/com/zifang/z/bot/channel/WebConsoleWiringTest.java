package com.zifang.z.bot.channel;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * p28a §控制台 —— <b>控制台页里"接线"这一层的结构卫兵</b>。
 *
 * <p>这一页在 09-26 实测出过三处断接线，三处的共同形状都不是"逻辑写错"而是<b>名字没接上</b>：
 * {@code loadCommands()} 定义好了却没有任何调用点（于是命令表恒空）、{@code newSession} 被后一份
 * 同名声明静默覆盖（于是"新会话"按钮走的是假实现）、{@code autoResize} 定义了却没绑上监听。
 * 业务断言抓不到它们 —— 界面照常渲染，只是那条路径永远不跑。</p>
 *
 * <p>本类不测行为，只钉三个由字节结构决定的量（量的是 <b>classpath 里被 serve 出去的那一份</b>，
 * 与 {@code HttpChannel} 的 {@code /index.html} 同源）：</p>
 * <ol>
 *   <li><b>同名函数声明只能有一次</b>：JS 里后声明静默赢，前一份变死代码，而页面全绿；</li>
 *   <li><b>每个声明出去的函数都要有第二个名字出现点</b>（去掉注释后），否则它就是 {@code loadCommands}；</li>
 *   <li><b>内联 {@code on*} 处理器里点名的每个函数都必须已声明</b>，否则运行时是 {@code ReferenceError}。</li>
 * </ol>
 *
 * <p>判据本身也要有牙：{@link #auditToolRejectsTheThreeHistoricalDefects()} 拿三份合成页把上面三条
 * 各打一遍（外加一份"健康页必须判无问题"的阴性对照），因为一条从没红过的静态尺和空跑无法区分。</p>
 */
public class WebConsoleWiringTest {

    private static final Pattern FN_DECL =
            Pattern.compile("\\b(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern HANDLER =
            Pattern.compile("\\son[a-zA-Z]+\\s*=\\s*\"([^\"]*)\"");
    /** 引用点：名字前后不能是标识符字符，且不能是 {@code obj.name} 的属性访问。 */
    private static final String REF_PREFIX = "(?<![\\w$.])";

    /** 内联处理器里会写成 {@code fn(...)} 形状、但根本不是本页函数的关键字 / 宿主函数。 */
    private static final Set<String> NOT_PAGE_FUNCTIONS = new LinkedHashSet<String>(Arrays.asList(
            "if", "for", "while", "switch", "catch", "return", "typeof", "function", "new",
            "else", "do", "try", "delete", "void", "in", "of", "await", "async", "yield", "throw",
            "alert", "confirm", "JSON", "String", "Number", "Boolean", "Array", "Object", "Date",
            "Math", "parseInt", "parseFloat", "isNaN", "encodeURIComponent", "setTimeout", "clearTimeout"));

    /** 审计结果：三条判据各自点到的名字。 */
    static final class Findings {
        final List<String> duplicated = new ArrayList<String>();
        final List<String> orphaned = new ArrayList<String>();
        final List<String> dangling = new ArrayList<String>();

        boolean clean() {
            return duplicated.isEmpty() && orphaned.isEmpty() && dangling.isEmpty();
        }

        @Override
        public String toString() {
            return "重复声明=" + duplicated + " 声明了没人接=" + orphaned + " 接线指向不存在的函数=" + dangling;
        }
    }

    /**
     * 去掉 JS 注释，好让"注释里提到过的名字"不算接线。
     *
     * <p>单双引号字符串整段照抄（字符串里的 {@code //} 不是注释）；<b>反引号模板串按代码对待</b> ——
     * 这一页的 {@code `${commandHelpText()}`} 就长在模板串里，把它当字符串剥掉会造出假孤儿。
     * 实测本页模板串内 0 处 {@code //}，所以这条路没有反例。</p>
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\'' || c == '"') {
                out.append(c);
                int j = i + 1;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        out.append(d).append(src.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    out.append(d);
                    j++;
                    if (d == c) {
                        break;
                    }
                }
                i = j;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int nl = src.indexOf("\n", i);
                i = nl < 0 ? n : nl;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    static Findings audit(String page) {
        String code = stripComments(page);
        Findings f = new Findings();

        Set<String> declared = new LinkedHashSet<String>();
        Set<String> seenOnce = new LinkedHashSet<String>();
        Matcher d = FN_DECL.matcher(code);
        while (d.find()) {
            String name = d.group(1);
            if (!seenOnce.add(name)) {
                if (!f.duplicated.contains(name)) {
                    f.duplicated.add(name);
                }
            }
            declared.add(name);
        }

        for (String name : new ArrayList<String>(declared)) {
            int occurrences = countMatches(REF_PREFIX + Pattern.quote(name) + "(?![\\w$])", code);
            if (occurrences <= 1) {
                f.orphaned.add(name);
            }
        }

        Matcher h = HANDLER.matcher(code);
        while (h.find()) {
            Matcher call = Pattern.compile(REF_PREFIX + "([A-Za-z_$][\\w$]*)\\s*\\(").matcher(h.group(1));
            while (call.find()) {
                String name = call.group(1);
                if (!declared.contains(name) && !NOT_PAGE_FUNCTIONS.contains(name)
                        && !f.dangling.contains(name)) {
                    f.dangling.add(name);
                }
            }
        }
        return f;
    }

    private static int countMatches(String regex, String text) {
        Matcher m = Pattern.compile(regex).matcher(text);
        int c = 0;
        while (m.find()) {
            c++;
        }
        return c;
    }

    /** 量的是 {@code HttpChannel} 真能吐出去的那份字节，不是 src 目录里的另一个副本。 */
    private static String consolePage() throws IOException {
        InputStream in = HttpChannel.class.getResourceAsStream("/web/index.html");
        assertTrue("classpath 里没有 /web/index.html —— 这一页不在这棵树上，本类无话可说", in != null);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buf.write(chunk, 0, read);
        }
        in.close();
        String page = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        assertFalse("读到的控制台页是空的（尺没跑，不是页面没问题）", page.trim().isEmpty());
        assertTrue("读到的不像一页 HTML：" + page.substring(0, Math.min(60, page.length())),
                page.contains("<script"));
        return page;
    }

    @Test
    public void noFunctionIsDeclaredTwiceOnTheConsolePage() throws Exception {
        Findings f = audit(consolePage());
        assertTrue("同名函数声明了两次 ⇒ 后一份静默赢、前一份变死代码，而界面照常渲染。" + f,
                f.duplicated.isEmpty());
    }

    @Test
    public void everyDeclaredConsoleFunctionHasACallSite() throws Exception {
        Findings f = audit(consolePage());
        assertTrue("这些函数定义了却没有任何第二个名字出现点（当年 loadCommands/autoResize 就是这个形状）。" + f,
                f.orphaned.isEmpty());
    }

    @Test
    public void everyInlineHandlerNamesAnExistingConsoleFunction() throws Exception {
        Findings f = audit(consolePage());
        assertTrue("内联 on* 处理器点名了不存在的函数 ⇒ 点下去就是 ReferenceError。" + f,
                f.dangling.isEmpty());
    }

    /**
     * 判据的牙：三份合成页各自复现一种真出过的缺陷，再加一份健康页的阴性对照。
     *
     * <p>少了这一支，上面三条在"尺本身看不见东西"时会全程绿 —— 而绿和空跑长得一样。</p>
     */
    @Test
    public void auditToolRejectsTheThreeHistoricalDefects() {
        String duplicated = "<script>\nfunction newSession() { real(); }\n"
                + "function newSession() { fake(); }\nfunction real() { }\nfunction fake() { }\n</script>";
        Findings f1 = audit(duplicated);
        assertEquals("同名声明必须被点名：" + f1, Arrays.asList("newSession"), f1.duplicated);

        String orphan = "<script>\nfunction loadCommands() { render(); }\nfunction render() { render2(); }\n"
                + "function render2() { }\n</script>";
        Findings f2 = audit(orphan);
        assertTrue("定义了却没人调的 loadCommands 必须被点名：" + f2, f2.orphaned.contains("loadCommands"));

        String dangling = "<script>\nfunction keep() { keep2(); }\nfunction keep2() { }\n"
                + "</script>\n<button onclick=\"reloadTools()\">x</button>";
        Findings f3 = audit(dangling);
        assertEquals("内联处理器指向不存在的函数必须被点名：" + f3,
                Arrays.asList("reloadTools"), f3.dangling);

        String healthy = "<script>\nfunction keep() { keep2(); }\nfunction keep2() { }\n"
                + "</script>\n<button onclick=\"keep()\">x</button>";
        Findings f4 = audit(healthy);
        assertTrue("健康页不该被冤枉（否则三条判据全是恒假红）：" + f4, f4.clean());

        // 注释里的名字不算接线：loadCommands 声明在代码里、却只被注释提到，必须仍判孤儿
        String onlyInComment = "<script>\n// 这里以前调 loadCommands() 的\n/* loadCommands() */\n"
                + "function loadCommands() { render(); }\nfunction render() { render2(); }\n"
                + "function render2() { }\n</script>";
        Findings f5 = audit(onlyInComment);
        assertTrue("只写在注释里的接线不该让尺闭嘴：" + f5,
                f5.orphaned.contains("loadCommands"));
    }
}
