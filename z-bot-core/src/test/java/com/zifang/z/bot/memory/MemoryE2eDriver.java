package com.zifang.z.bot.memory;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P24 杠③ 的真进程驱动（<b>不是测试</b>，没有 {@code @Test}，surefire 不收）。
 *
 * <p>存在的理由：工单要求"起真 jar、真文件、真重启"，而记忆这一层在生产里唯一的入口是
 * agent 循环（要打模型）。所以这个 main 只做一件事 —— <b>用真构件里的真装配入口</b>
 * （{@link MemoryTools#memoryTool(MemoryStore)} 这个被 {@code BotAgent:1866} 注册的工具、
 * 以及 {@link MemoryStore} 的公开写通道）跑一次真写入，把现场事实打成
 * {@code FACT k=v} 一行一行吐给 python；判定与 md5 由 python 在 JVM 外面独立量，
 * 驱动自己说的话一句不算证据。</p>
 *
 * <p>用法：{@code java -cp <真 jar>:<test-classes> 本类 <mode> <memories 目录> [参数…]}。
 * mode：tool|read|entries|soul|halfwrite|badbudget|driftprobe，见 {@link #main}。</p>
 */
public final class MemoryE2eDriver {

    private MemoryE2eDriver() {
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length < 2) {
            System.out.println("FACT usage=需要 <mode> <memories 目录>");
            System.out.println("FACT result=FATAL");
            System.exit(2);
        }
        String mode = argv[0];
        File dir = new File(argv[1]);
        List<String> rest = new ArrayList<String>();
        for (int i = 2; i < argv.length; i++) {
            rest.add(argv[i]);
        }
        System.out.println("FACT mode=" + mode);
        System.out.println("FACT dir=" + dir.getAbsolutePath());
        System.out.println("FACT dir_exists=" + dir.isDirectory());
        // 这一行是"真构件"的证：产品类必须从被验的那个 jar 里加载出来，
        // 而不是从 target/classes 里就地跑（后者只能证明我编译过）。
        System.out.println("FACT loaded_from="
                + MemoryStore.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("FACT java=" + System.getProperty("java.version"));
        int rc = 0;
        if ("tool".equals(mode)) {
            rc = viaTool(dir, rest);
        } else if ("read".equals(mode)) {
            rc = read(dir);
        } else if ("soul".equals(mode)) {
            rc = soul(dir);
        } else if ("halfwrite".equals(mode)) {
            rc = halfWrite(dir, rest);
        } else if ("badbudget".equals(mode)) {
            rc = badBudget(dir, rest);
        } else if ("driftprobe".equals(mode)) {
            rc = driftProbe(dir);
        } else {
            System.out.println("FACT unknown_mode=" + mode);
            rc = 3;
        }
        System.out.println("FACT result=" + (rc == 0 ? "OK" : "NONZERO"));
        System.out.flush();
        System.exit(rc);
    }

    // ===== 走真工具面（BotAgent 注册的就是这一个 Tool）=====

    private static int viaTool(File dir, List<String> rest) {
        // tool <action> [section=…] [content=…] [old_text=…] [operations=…] [confirmed=true]
        MemoryStore store = new MemoryStore(dir);
        Tool tool = MemoryTools.memoryTool(store);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        for (String kv : rest) {
            int eq = kv.indexOf('=');
            if (eq < 0) {
                System.out.println("FACT bad_arg=" + kv);
                return 2;
            }
            args.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        ToolResult r = tool.execute(args);
        System.out.println("FACT tool_action=" + args.get("action"));
        System.out.println("FACT tool_error=" + r.isError());
        System.out.println("FACT tool_text=" + oneLine(r.getContent()));
        Map<String, Object> meta = r.getMetadata();
        System.out.println("FACT gate_code=" + (meta == null ? "-" : String.valueOf(meta.get("memoryGateCode"))));
        System.out.println("FACT drift_backup=" + (meta == null ? "-" : String.valueOf(meta.get("memoryDriftBackup"))));
        System.out.println("FACT needs_confirmation=" + (meta != null && Boolean.TRUE.equals(meta.get(ConfirmationsKey.KEY))));
        System.out.println("FACT on_disk_md5=" + md5(new File(dir, "MEMORY.md")));
        System.out.println("FACT user_md5=" + md5(new File(dir, "USER.md")));
        return r.isError() ? 1 : 0;
    }

    /** {@link com.zifang.z.bot.tool.Confirmations} 的键名（不引那个类，避免驱动反向依赖 bot.tool 包）。 */
    private static final class ConfirmationsKey {
        static final String KEY = "needsConfirmation";
    }

    private static int read(File dir) {
        MemoryStore store = new MemoryStore(dir);
        System.out.println("FACT memory_entries=" + store.entries(MemorySection.MEMORY).size());
        System.out.println("FACT user_entries=" + store.entries(MemorySection.USER).size());
        System.out.println("FACT memory_bodies=" + join(store.entryBodies(MemorySection.MEMORY)));
        System.out.println("FACT user_bodies=" + join(store.entryBodies(MemorySection.USER)));
        System.out.println("FACT empty=" + store.isEmpty());
        System.out.println("FACT soul_exists=" + store.soulExists());
        System.out.println("FACT char_count=" + store.charCount(MemorySection.MEMORY));
        System.out.println("FACT char_limit=" + store.charLimit(MemorySection.MEMORY));
        System.out.println("FACT md5=" + md5(new File(dir, "MEMORY.md")));
        for (File f : list(dir)) {
            System.out.println("FACT file=" + f.getName());
        }
        return 0;
    }

    private static int soul(File dir) throws Exception {
        MemoryStore store = new MemoryStore(dir);
        store.ensureSoul();
        File soul = new File(dir, "SOUL.md");
        System.out.println("FACT soul_md5=" + md5(soul));
        System.out.println("FACT soul_bytes=" + (soul.isFile() ? Files.size(soul.toPath()) : -1));
        store.ensureSoul();
        System.out.println("FACT soul_md5_after_second_ensure=" + md5(soul));
        return 0;
    }

    /** 半写：换一个只写前 3 个字节的落盘末端，看快照/还原/回读对账在真目录上成不成立。 */
    private static int halfWrite(File dir, List<String> rest) {
        String text = rest.isEmpty() ? "半写的一行" : rest.get(0);
        MemoryStore clean = new MemoryStore(dir);
        System.out.println("FACT before_md5=" + md5(new File(dir, "MEMORY.md")));
        MemoryStore broken = new MemoryStore(dir, 2200, 1375, new MemoryStore.WriteSink() {
            @Override
            public void write(File target, byte[] payload) throws IOException {
                Files.write(target.toPath(), java.util.Arrays.copyOf(payload, 3));
            }
        });
        try {
            broken.appendEntry(MemorySection.MEMORY, text);
            System.out.println("FACT gate=NOT_REJECTED");
            return 1;
        } catch (MemoryWriteRejectedException e) {
            System.out.println("FACT gate=" + e.code());
            System.out.println("FACT bak=" + String.valueOf(e.bakPath()));
            System.out.println("FACT bak_exists=" + (e.bakPath() != null && new File(e.bakPath()).isFile()));
        } catch (IOException e) {
            System.out.println("FACT gate=IO_" + e.getClass().getSimpleName());
            return 1;
        }
        System.out.println("FACT after_md5=" + md5(new File(dir, "MEMORY.md")));
        System.out.println("FACT tmp_left=" + new File(dir, "MEMORY.md.tmp").exists());
        System.out.println("FACT entries_now=" + clean.entries(MemorySection.MEMORY).size());
        return 0;
    }

    /** 真预算：把 USER 的预算压到 60，越界必须拒并且一个字节不落。 */
    private static int badBudget(File dir, List<String> rest) {
        String text = rest.isEmpty() ? "超长的一行" : rest.get(0);
        MemoryStore tight = new MemoryStore(dir, 2200, 60);
        System.out.println("FACT user_before_md5=" + md5(new File(dir, "USER.md")));
        try {
            MemoryReceipt r = tight.appendEntry(MemorySection.USER, text);
            System.out.println("FACT gate=WROTE " + r.usage());
            return 1;
        } catch (MemoryWriteRejectedException e) {
            System.out.println("FACT gate=" + e.code());
        } catch (IOException e) {
            System.out.println("FACT gate=" + e.getClass().getSimpleName());
            return 1;
        }
        System.out.println("FACT user_after_md5=" + md5(new File(dir, "USER.md")));
        return 0;
    }

    /** 只读探针：外部（python）改过盘之后，本进程看到的漂移信号是什么。 */
    private static int driftProbe(File dir) {
        MemoryDriftGuard.Drift d = MemoryDriftGuard.detect(new File(dir, "MEMORY.md"),
                MemorySection.MEMORY);
        System.out.println("FACT drift=" + (d == null ? "none" : d.signal()));
        System.out.println("FACT drift_detail=" + (d == null ? "-" : oneLine(d.detail())));
        return 0;
    }

    // ===== helpers =====

    private static List<File> list(File dir) {
        File[] all = dir.listFiles();
        List<File> out = new ArrayList<File>();
        if (all != null) {
            java.util.Arrays.sort(all);
            for (File f : all) {
                out.add(f);
            }
        }
        return out;
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) {
                sb.append("|");
            }
            sb.append(x);
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "-" : s.replaceAll("\\s*\n\\s*", " ⏎ ");
    }

    private static String md5(File f) {
        try {
            if (!f.isFile()) {
                return "absent";
            }
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(Files.readAllBytes(f.toPath()));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", Byte.valueOf(b)));
            }
            return sb.toString();
        } catch (Exception e) {
            return "ERR_" + e.getClass().getSimpleName();
        }
    }

    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
