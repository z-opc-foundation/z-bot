package com.zifang.z.bot.sandbox;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * macOS Seatbelt profile 构建。
 */
final class MacSeatbeltProfileBuilder {

    private static final String LOG_TAG = "z-team-sandbox";

    private MacSeatbeltProfileBuilder() {
    }

    static String build(SandboxConfig config) {
        String workDir = SandboxPathUtil.normalize(config.getWorkingDirectory());
        boolean networkRestricted = config.isNetworkRestricted();

        StringBuilder sb = new StringBuilder(8192);
        sb.append("(version 1)\n");
        sb.append("(deny default (with message \"").append(LOG_TAG).append("\"))\n");
        sb.append("; LogTag: ").append(LOG_TAG).append('\n');
        appendEssentialPermissions(sb);
        appendReadRules(sb, workDir);
        appendWriteRules(sb, config, workDir);
        appendNetworkRules(sb, networkRestricted);
        return sb.toString();
    }

    private static void appendEssentialPermissions(StringBuilder sb) {
        sb.append("; Process\n");
        sb.append("(allow process-exec)\n");
        sb.append("(allow process-fork)\n");
        sb.append("(allow process-info* (target same-sandbox))\n");
        sb.append("(allow signal (target same-sandbox))\n");
        sb.append("(allow mach-priv-task-port (target same-sandbox))\n");
        sb.append("(allow user-preference-read)\n");
        sb.append("(allow pseudo-tty)\n");
        sb.append("(allow ipc-posix-shm)\n");
        sb.append("(allow ipc-posix-sem)\n");
        sb.append("(allow distributed-notification-post)\n");
        sb.append("(allow system-socket (require-all (socket-domain AF_SYSTEM) (socket-protocol 2)))\n");
        appendMachLookup(sb);
        appendSysctl(sb);
        appendDevIoctl(sb);
    }

    private static void appendReadRules(StringBuilder sb, String workDir) {
        sb.append("; File read\n");
        sb.append("(allow file-read*)\n");
        for (String deny : SandboxPolicies.mandatoryDenyPaths(workDir)) {
            appendDenySubpath(sb, "file-read*", deny);
        }
    }

    private static void appendWriteRules(StringBuilder sb, SandboxConfig config, String workDir) {
        sb.append("; File write\n");
        Set<String> allowWrites = new LinkedHashSet<String>();
        allowWrites.add(workDir);
        allowWrites.addAll(SandboxPolicies.defaultWritePaths(workDir));
        for (String extra : config.getAdditionalWritePaths()) {
            allowWrites.add(SandboxPathUtil.normalize(extra));
        }
        for (String allow : allowWrites) {
            appendAllowSubpath(sb, "file-write*", allow);
        }
        for (String deny : macMandatoryWriteDenyPaths(workDir)) {
            appendDenySubpath(sb, "file-write*", deny);
        }
    }

    private static List<String> macMandatoryWriteDenyPaths(String workDir) {
        return SandboxPolicies.mandatoryDenyPaths(workDir);
    }

    private static void appendAllowSubpath(StringBuilder sb, String operation, String path) {
        sb.append("(allow ").append(operation).append('\n');
        sb.append("  (subpath ").append(SandboxPathUtil.escapeSeatbeltPath(path)).append(")\n");
        sb.append("  (with message \"").append(LOG_TAG).append("\"))\n");
    }

    private static void appendDenySubpath(StringBuilder sb, String operation, String path) {
        sb.append("(deny ").append(operation).append('\n');
        sb.append("  (subpath ").append(SandboxPathUtil.escapeSeatbeltPath(path)).append(")\n");
        sb.append("  (with message \"").append(LOG_TAG).append("\"))\n");
    }

    private static void appendMachLookup(StringBuilder sb) {
        sb.append("(allow mach-lookup\n");
        sb.append("  (global-name \"com.apple.logd\")\n");
        sb.append("  (global-name \"com.apple.system.logger\")\n");
        sb.append("  (global-name \"com.apple.lsd.mapdb\")\n");
        sb.append("  (global-name \"com.apple.fonts\")\n");
        sb.append("  (global-name \"com.apple.coreservices.launchservicesd\")\n");
        sb.append("  (global-name \"com.apple.securityd.xpc\")\n");
        sb.append("  (global-name \"com.apple.system.opendirectoryd.libinfo\")\n");
        sb.append("  (global-name \"com.apple.system.opendirectoryd.membership\")\n");
        sb.append(")\n");
    }

    private static void appendSysctl(StringBuilder sb) {
        sb.append("(allow sysctl-read\n");
        sb.append("  (sysctl-name \"hw.ncpu\")\n");
        sb.append("  (sysctl-name \"hw.physicalcpu\")\n");
        sb.append("  (sysctl-name \"hw.logicalcpu\")\n");
        sb.append("  (sysctl-name \"hw.memsize\")\n");
        sb.append("  (sysctl-name \"hw.pagesize\")\n");
        sb.append("  (sysctl-name \"kern.hostname\")\n");
        sb.append("  (sysctl-name \"kern.osversion\")\n");
        sb.append("  (sysctl-name \"kern.osrelease\")\n");
        sb.append("  (sysctl-name \"kern.version\")\n");
        sb.append("  (sysctl-name-prefix \"kern.proc.\")\n");
        sb.append(")\n");
    }

    private static void appendDevIoctl(StringBuilder sb) {
        sb.append("(allow file-ioctl (literal \"/dev/null\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/zero\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/tty\"))\n");
        sb.append("(allow file-read* file-write* (literal \"/dev/null\"))\n");
        sb.append("(allow file-read* file-write* (literal \"/dev/zero\"))\n");
        sb.append("(allow file-read* file-write* (literal \"/dev/tty\"))\n");
        sb.append("(allow file-ioctl file-read-data file-write-data\n");
        sb.append("  (require-all (literal \"/dev/null\") (vnode-type CHARACTER-DEVICE)))\n");
    }

    private static void appendNetworkRules(StringBuilder sb, boolean networkRestricted) {
        sb.append("; Network\n");
        if (!networkRestricted) {
            sb.append("(allow network*)\n");
        }
    }
}
