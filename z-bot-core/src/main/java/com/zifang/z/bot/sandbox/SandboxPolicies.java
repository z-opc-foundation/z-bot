package com.zifang.z.bot.sandbox;

import java.io.File;
import java.util.*;

/**
 * 沙箱安全策略与敏感路径规则。
 */
public final class SandboxPolicies {

    public static final List<String> DANGEROUS_FILES = Collections.unmodifiableList(Arrays.asList(
            ".gitconfig",
            ".gitmodules",
            ".bashrc",
            ".bash_profile",
            ".zshrc",
            ".zprofile",
            ".profile",
            ".ripgreprc",
            ".mcp.json"
    ));

    public static final List<String> DANGEROUS_DIRECTORIES = Collections.unmodifiableList(Arrays.asList(
            ".vscode",
            ".idea",
            ".claude/commands",
            ".claude/agents"
    ));

    private SandboxPolicies() {
    }

    /**
     * 工作区内需屏蔽读写的敏感路径（挂载为 /dev/null 或 Seatbelt deny）。
     */
    public static List<String> mandatoryDenyPaths(String workDir) {
        Set<String> paths = new LinkedHashSet<String>();
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            home = System.getProperty("user.home", "");
        }

        if (home != null && !home.isEmpty()) {
            paths.add(join(home, ".ssh"));
            paths.add(join(home, ".aws"));
            paths.add(join(home, ".gnupg"));
            paths.add(join(home, ".gitconfig"));
            paths.add(join(home, ".netrc"));
            paths.add(join(home, ".docker"));
        }
        paths.add("/root");
        paths.add("/root/.ssh");

        String resolvedWork = SandboxPathUtil.normalize(workDir);
        for (String name : DANGEROUS_FILES) {
            paths.add(join(resolvedWork, name));
            if (home != null && !home.isEmpty()) {
                paths.add(join(home, name));
            }
        }
        for (String dir : DANGEROUS_DIRECTORIES) {
            paths.add(join(resolvedWork, dir));
        }

        File dotGit = new File(resolvedWork, ".git");
        if (dotGit.isDirectory()) {
            paths.add(join(resolvedWork, ".git", "hooks"));
            paths.add(join(resolvedWork, ".git", "config"));
        }

        List<String> result = new ArrayList<String>();
        for (String p : paths) {
            result.add(SandboxPathUtil.normalize(p));
        }
        return result;
    }

    /**
     * 除工作目录外，命令运行通常需要的可写系统路径。
     */
    public static List<String> defaultWritePaths(String workDir) {
        List<String> paths = new ArrayList<String>();
        paths.add("/dev/stdout");
        paths.add("/dev/stderr");
        paths.add("/dev/null");
        paths.add("/dev/tty");
        paths.add("/dev/dtracehelper");
        paths.add("/tmp");
        paths.add("/private/tmp");
        paths.add("/var/tmp");
        paths.add("/private/var/tmp");

        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            home = System.getProperty("user.home", "");
        }
        if (home != null && !home.isEmpty()) {
            paths.add(join(home, ".npm", "_logs"));
        }
        paths.add(SandboxPathUtil.normalize(workDir));
        return paths;
    }

    private static String join(String base, String... parts) {
        File f = new File(base);
        for (String part : parts) {
            f = new File(f, part);
        }
        return f.getPath();
    }
}
