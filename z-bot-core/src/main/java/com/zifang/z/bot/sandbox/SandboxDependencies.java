package com.zifang.z.bot.sandbox;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱运行时依赖检查。
 */
public final class SandboxDependencies {

    private SandboxDependencies() {
    }

    public static boolean isSupported() {
        return SandboxPlatform.detect() != SandboxPlatform.UNSUPPORTED;
    }

    /**
     * Seatbelt 真正可用吗？sandbox-exec 存在不等于能用：
     * 部分 macOS（实测 26.x，SIP 开启）对任何含 deny default 的 profile
     * 直接 sandbox_apply: Operation not permitted（exit 71）。
     * 用最小 profile 实跑 /bin/true 探测（约十几毫秒），失败即不可用。
     */
    public static boolean isSeatbeltUsable() {
        if (SandboxPlatform.detect() != SandboxPlatform.MACOS) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(
                    "/usr/bin/sandbox-exec", "-p", "(version 1)(deny default)", "/bin/true")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<String> check(SandboxConfig config) {
        List<String> errors = new ArrayList<String>();
        SandboxPlatform platform = SandboxPlatform.detect();
        if (platform == SandboxPlatform.UNSUPPORTED) {
            errors.add("unsupported OS: only Linux and macOS are supported");
            return errors;
        }
        if (platform == SandboxPlatform.LINUX) {
            String bwrap = resolveBwrap(config);
            if (!isExecutable(bwrap)) {
                errors.add("bubblewrap (bwrap) not found; install: apt install bubblewrap");
            }
        }
        if (platform == SandboxPlatform.MACOS) {
            if (!isExecutable("/usr/bin/sandbox-exec")) {
                errors.add("sandbox-exec not found on macOS");
            }
        }
        File work = new File(SandboxPathUtil.normalize(config.getWorkingDirectory()));
        if (!work.exists() && !work.mkdirs()) {
            errors.add("cannot create working directory: " + work.getAbsolutePath());
        }
        return errors;
    }

    static String resolveBwrap(SandboxConfig config) {
        if (config.getBwrapPath() != null && !config.getBwrapPath().trim().isEmpty()) {
            return config.getBwrapPath().trim();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return "bwrap";
        }
        for (String dir : path.split(File.pathSeparator)) {
            File candidate = new File(dir, "bwrap");
            if (candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return "bwrap";
    }

    private static boolean isExecutable(String path) {
        File f = new File(path);
        return f.canExecute();
    }
}
