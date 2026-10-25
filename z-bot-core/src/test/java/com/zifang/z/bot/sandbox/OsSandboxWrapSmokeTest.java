package com.zifang.z.bot.sandbox;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OS 级沙箱真机冒烟（macOS Seatbelt）：包出来的命令真的跑，且真的被关住。
 * 非 macOS 环境整组跳过（bwrap 路径由 Linux 侧另行验证）。
 */
public class OsSandboxWrapSmokeTest {

    private SandboxConfig config(File workDir) {
        return SandboxConfig.builder()
                .workingDirectory(workDir.getPath())
                .networkRestricted(true)
                .agentCode("zbot-smoke")
                .allowedCommands(null)
                .enabled(true)
                .build();
    }

    @Test
    public void wrappedEchoReallyRunsInsideSandbox() throws Exception {
        assumeTrue(SandboxPlatform.detect() == SandboxPlatform.MACOS, "仅 macOS");
        File work = Files.createTempDirectory("zbot-sbx-").toFile();
        List<String> wrapped = OsSandboxWrap.wrap(config(work), Arrays.asList("echo", "zbot-sbx-ok"));
        assertTrue(wrapped.get(0).contains("sandbox-exec"), "argv 应被 sandbox-exec 包裹: " + wrapped.get(0));
        SandboxExecResult r = SandboxProcessRunner.run(wrapped, work, 30L, 65536);
        assertTrue(r.isSuccess(), "沙箱内 echo 失败: " + r);
        assertTrue(r.getOutput().contains("zbot-sbx-ok"), "输出不对: " + r.getOutput());
    }

    @Test
    public void writeOutsideWorkdirIsDenied() throws Exception {
        assumeTrue(SandboxPlatform.detect() == SandboxPlatform.MACOS, "仅 macOS");
        File work = Files.createTempDirectory("zbot-sbx-").toFile();
        File probe = new File(System.getProperty("user.home"), "zbot-sbx-escape-probe");
        List<String> wrapped = OsSandboxWrap.wrap(config(work), Arrays.asList("touch", probe.getPath()));
        SandboxExecResult r = SandboxProcessRunner.run(wrapped, work, 30L, 65536);
        assertFalse(r.isSuccess(), "家目录写入不该成功: " + r);
        assertFalse(probe.exists(), "逃逸文件居然落盘了");
    }

    @Test
    public void sshDirReadIsDenied() throws Exception {
        assumeTrue(SandboxPlatform.detect() == SandboxPlatform.MACOS, "仅 macOS");
        File sshDir = new File(System.getProperty("user.home"), ".ssh");
        assumeTrue(sshDir.isDirectory(), "无 .ssh 目录可测");
        File[] files = sshDir.listFiles();
        assumeTrue(files != null && files.length > 0, ".ssh 目录为空");
        File work = Files.createTempDirectory("zbot-sbx-").toFile();
        List<String> wrapped = OsSandboxWrap.wrap(config(work),
                Arrays.asList("cat", files[0].getPath()));
        SandboxExecResult r = SandboxProcessRunner.run(wrapped, work, 30L, 65536);
        assertFalse(r.isSuccess(), ".ssh 读不该成功（mandatoryDenyPaths）: " + r);
    }
}
