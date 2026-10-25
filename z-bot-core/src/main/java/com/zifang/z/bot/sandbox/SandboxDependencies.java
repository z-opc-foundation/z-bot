package com.zifang.z.bot.sandbox;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱运行时依赖检查。
 */
public final class SandboxDependencies {

    private SandboxDependencies() {
    }

    public static boolean isSupported() {
        return SandboxPlatform.detect() != SandboxPlatform.UNSUPPORTED;
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
