package com.zifang.z.bot.sandbox;

import java.io.File;
import java.util.*;

/**
 * Linux bubblewrap 命令参数构建。
 */
final class LinuxBubblewrapCommandBuilder {

    /**
     * deny 目标按路径深度从深到浅排序，保证子路径先于父目录绑定。
     */
    private static final Comparator<String> COMPARE_BY_LENGTH_DESC =
            new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return Integer.compare(b.length(), a.length());
                }
            };

    private LinuxBubblewrapCommandBuilder() {
    }

    static List<String> buildWrapCommand(SandboxConfig config, String innerShellCommand) {
        List<String> args = buildBaseArgs(config);
        args.add(resolveShell(config));
        args.add("-c");
        args.add(innerShellCommand);
        return args;
    }

    static List<String> buildWrapArgv(SandboxConfig config, List<String> argv) {
        List<String> args = buildBaseArgs(config);
        for (String part : argv) {
            args.add(part);
        }
        return args;
    }

    private static List<String> buildBaseArgs(SandboxConfig config) {
        String workDir = SandboxPathUtil.normalize(config.getWorkingDirectory());

        List<String> args = new ArrayList<String>();
        args.add(SandboxDependencies.resolveBwrap(config));
        args.add("--new-session");
        args.add("--die-with-parent");
        args.add("--ro-bind");
        args.add("/");
        args.add("/");

        Set<String> writePaths = new LinkedHashSet<String>();
        writePaths.addAll(SandboxPolicies.defaultWritePaths(workDir));
        writePaths.add(workDir);
        for (String extra : config.getAdditionalWritePaths()) {
            writePaths.add(SandboxPathUtil.normalize(extra));
        }

        for (String writePath : writePaths) {
            if (writePath.startsWith("/dev/")) {
                continue;
            }
            File f = new File(writePath);
            if (!f.exists()) {
                continue;
            }
            if (isUnder(workDir, writePath)) {
                continue;
            }
            args.add("--bind");
            args.add(writePath);
            args.add(writePath);
        }

        args.add("--bind");
        args.add(workDir);
        args.add(workDir);

        // 子路径先绑定、父目录后绑定：避免父目录先被覆盖后子路径 mount 目标失效（ENOTDIR）。
        List<String> denies = SandboxPolicies.mandatoryDenyPaths(workDir);
        denies = new ArrayList<String>(denies);
        denies.sort(COMPARE_BY_LENGTH_DESC);
        for (String deny : denies) {
            File target = new File(deny);
            if (!target.exists()) {
                // 宿主上不存在的路径，在沙箱内（--ro-bind / /）同样不存在，无需屏蔽；
                // 且 bwrap 会尝试创建 bind 目标，在只读 rootfs（如 K8s 容器）上会直接启动失败：
                //   bwrap: Can't create file at /root/.ssh: Read-only file system
                continue;
            }
            if (target.isDirectory()) {
                // bwrap 0.4.0 不允许把文件（/dev/null）bind 到已存在的目录目标：
                //   bwrap: Can't create file at /root: Is a directory
                // 目录型敏感路径用空 tmpfs 覆盖并重新挂载为只读：空 + 只读，语义等价；
                // 且 tmpfs 由内核创建，不依赖宿主可写位置，无"创建失败"路径，杜绝 fail-open 兜底。
                args.add("--tmpfs");
                args.add(deny);
                args.add("--remount-ro");
                args.add(deny);
            } else {
                args.add("--ro-bind");
                args.add("/dev/null");
                args.add(deny);
            }
        }

        if (config.isNetworkRestricted()) {
            args.add("--unshare-net");
        }

        args.add("--dev");
        args.add("/dev");

        args.add("--unshare-pid");
        if (config.isWeakerNestedSandbox()) {
            args.add("--unshare-user");
            args.add("--bind");
            args.add("/proc");
            args.add("/proc");
        } else {
            args.add("--proc");
            args.add("/proc");
        }

        args.add("--chdir");
        args.add(workDir);
        args.add("--setenv");
        args.add("SANDBOX_RUNTIME");
        args.add("1");
        args.add("--setenv");
        args.add("TMPDIR");
        args.add(workDir);
        return args;
    }

    private static String resolveShell(SandboxConfig config) {
        if (config.getShell() != null && !config.getShell().trim().isEmpty()) {
            return config.getShell().trim();
        }
        if (new File("/bin/bash").canExecute()) {
            return "/bin/bash";
        }
        return "/bin/sh";
    }

    private static boolean isUnder(String parent, String child) {
        String p = parent.endsWith(File.separator) ? parent : parent + File.separator;
        return child.equals(parent) || child.startsWith(p);
    }
}
