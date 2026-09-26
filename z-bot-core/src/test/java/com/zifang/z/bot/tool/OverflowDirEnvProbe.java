package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.ToolDescriptor;
import com.zifang.z.agent.kernel.tool.ToolResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>子进程探针</b>（不是测试类，名字里没有 Test，surefire 不会把它当用例收）：
 * 替 {@link ToolkitResultCapTest} 把"溢出目录第三级解析 {@code $ZBOT_HOME}"这一级跑在一个
 * <b>真带着该环境变量</b>的 JVM 里。
 *
 * <p>为什么必须起子 JVM：{@code System.getenv} 在本进程里改不了，而 {@link Toolkit#ZBOT_HOME_ENV}
 * 这一级只有在进程环境里存在才可能被判到 —— 上一棒（p20b）在 EVIDENCE §2.5 记的就是这条结构洞：
 * "JUnit 里改不了本进程 env ⇒ 这一支永远不被断言 ⇒ 注入摘掉它测试照常绿"。</p>
 *
 * <p>探针刻意<b>不</b>调 {@code setOverflowDir}、也<b>不</b>设
 * {@code -Dzbot.tool.result.dir}（那是第一、二级），于是第三级 {@code $ZBOT_HOME}
 * 是唯一可能命中的那一级；解析结果与"超限正文到底落在哪儿"都用 {@code PROBE_*} 一行一条打到
 * stdout，由测试侧逐条判。父进程会把 {@code -Duser.home} 指到临时假 home，所以就算有人把解析
 * 改回写死 {@code ~/.zbot}，写出去的也只是假 home 里的东西（红线 1 / 杠④）。</p>
 *
 * <p>只打印、不抛：任何异常都转成 {@code PROBE_FATAL=} 一行并以非 0 退出，让测试当场判红而不是
 * 静悄悄少打几行。</p>
 */
public final class OverflowDirEnvProbe {

    /** args: [正文长度, 填充字符]。 */
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("PROBE_FATAL=" + t);
            System.out.flush();
            System.exit(9);
        }
    }

    private static void run(String[] args) throws Exception {
        int len = Integer.parseInt(args[0]);
        char fill = args[1].charAt(0);
        String body = repeat(len, fill);

        System.out.println("PROBE_ZBOT_HOME_ENV=" + orNull(System.getenv(Toolkit.ZBOT_HOME_ENV)));
        System.out.println("PROBE_USER_HOME=" + new File(System.getProperty("user.home"))
                .getAbsolutePath());

        Toolkit tk = new Toolkit();
        // 第一级（显式注入）与第二级（-D）都不给 ⇒ 只有 $ZBOT_HOME 这一级能解析出东西
        File resolved = tk.getOverflowDir();
        System.out.println("PROBE_OVERFLOW_DIR="
                + (resolved == null ? "NULL" : resolved.getAbsolutePath()));

        tk.setMaxResultChars(1_000L);
        tk.setPreviewChars(200L);
        tk.register(Toolkit.of("probed", "探针工具", null,
                        a -> ToolResult.text(String.valueOf(a.get("payload")))),
                new ToolDescriptor(Toolsets.CORE, false, null, Toolkit.DEFAULT_OWNER,
                        30_000L, 60_000L, ToolDescriptor.NO_MAX_RESULT_CHARS, null));
        ToolResult r = tk.execute("probed", Collections.<String, Object>singletonMap("payload", body));

        boolean truncated = r.getContent().contains("[结果 ");
        System.out.println("PROBE_TRUNCATED=" + (truncated ? "yes" : "no"));
        System.out.println("PROBE_CONTENT_LEN=" + r.getContent().length());
        int note = r.getContent().indexOf("\n\n[结果 ");
        System.out.println("PROBE_NOTE=" + (note < 0 ? "" :
                r.getContent().substring(note).replace("\n", " ").replace("\r", " ")));

        List<String> names = new ArrayList<String>();
        if (resolved == null) {
            System.out.println("PROBE_DIR_LIST=NO-DIR");
            System.out.println("PROBE_DIR_COUNT=NONE");
            System.out.println("PROBE_SPILLED_BYTES=NONE");
            System.out.flush();
            return;
        }
        if (resolved.isDirectory()) {
            String[] listed = resolved.list();
            if (listed != null) {
                Collections.addAll(names, listed);
            }
            Collections.sort(names);
        }
        System.out.println("PROBE_DIR_LIST=" + (names.isEmpty() ? "EMPTY" : String.join(",", names)));
        System.out.println("PROBE_DIR_COUNT=" + names.size());
        if (names.size() == 1) {
            byte[] onDisk = Files.readAllBytes(new File(resolved, names.get(0)).toPath());
            System.out.println("PROBE_SPILLED_BYTES="
                    + new String(onDisk, StandardCharsets.UTF_8).length());
        } else {
            System.out.println("PROBE_SPILLED_BYTES=NONE");
        }
        System.out.flush();
    }

    private static String orNull(String s) {
        return s == null ? "NULL" : s;
    }

    private static String repeat(int len, char fill) {
        char[] buf = new char[len];
        java.util.Arrays.fill(buf, fill);
        return new String(buf);
    }

    private OverflowDirEnvProbe() {
    }
}
