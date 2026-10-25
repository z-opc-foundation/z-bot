package com.zifang.z.bot.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * OS 级沙箱的 argv 包装门面：只包命令、不执行、不过滤。
 *
 * <p>命令闸门（白名单/审批）归 {@code ExecGuard}，这里只负责把 argv 套进
 * macOS {@code sandbox-exec}（Seatbelt profile）或 Linux {@code bwrap}。
 * 配置键 {@code exec.env.os.sandbox=auto} 时由 {@code LocalExecEnvironment} 调用。</p>
 */
public final class OsSandboxWrap {

    private OsSandboxWrap() {
    }

    public static List<String> wrap(SandboxConfig config, List<String> argv) throws IOException {
        if (config == null || argv == null || argv.isEmpty()) {
            throw new IOException("os sandbox wrap: config/argv 为空");
        }
        SandboxPlatform platform = SandboxPlatform.detect();
        if (platform == SandboxPlatform.LINUX) {
            return LinuxBubblewrapCommandBuilder.buildWrapArgv(config, argv);
        }
        if (platform == SandboxPlatform.MACOS) {
            File profileFile = writeSeatbeltProfile(config);
            List<String> cmd = new ArrayList<String>(argv.size() + 3);
            cmd.add("/usr/bin/sandbox-exec");
            cmd.add("-f");
            cmd.add(profileFile.getAbsolutePath());
            cmd.addAll(argv);
            return cmd;
        }
        throw new IOException("os sandbox 不支持当前平台（仅 macOS/Linux），按显式失败处理，不裸跑");
    }

    private static File writeSeatbeltProfile(SandboxConfig config) throws IOException {
        String profile = MacSeatbeltProfileBuilder.build(config);
        File sb = File.createTempFile("zbot-sandbox-", ".sb");
        Files.write(sb.toPath(), profile.getBytes(StandardCharsets.UTF_8));
        sb.deleteOnExit();
        return sb;
    }
}
