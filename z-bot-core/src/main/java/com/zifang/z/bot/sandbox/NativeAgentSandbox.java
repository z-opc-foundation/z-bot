package com.zifang.z.bot.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 原生 OS 沙箱实现（Linux bubblewrap、macOS sandbox-exec）。
 */
public final class NativeAgentSandbox implements AgentSandbox {

    private final SandboxConfig config;
    private final String workDir;
    private final SandboxPlatform platform;
    private final File workDirFile;

    public NativeAgentSandbox(SandboxConfig config) {
        List<String> depErrors = SandboxDependencies.check(config);
        if (!depErrors.isEmpty()) {
            throw new IllegalStateException("sandbox dependencies: " + depErrors);
        }
        this.config = config;
        this.workDir = SandboxPathUtil.normalize(config.getWorkingDirectory());
        this.workDirFile = new File(workDir);
        if (!workDirFile.exists() && !workDirFile.mkdirs()) {
            throw new IllegalArgumentException("cannot create working directory: " + workDir);
        }
        this.platform = SandboxPlatform.detect();
        if (platform == SandboxPlatform.UNSUPPORTED) {
            throw new UnsupportedOperationException("sandbox only supports Linux and macOS");
        }
    }

    private static String resolveMacShell() {
        if (new File("/bin/bash").canExecute()) {
            return "/bin/bash";
        }
        return "/bin/sh";
    }

    @Override
    public String exec(String command) throws IOException {
        return execDetailed(command).toString();
    }

    @Override
    public SandboxExecResult execDetailed(String command) throws IOException {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("command is empty");
        }
        SandboxCommandFilter.checkCommand(config.toWhitelist(), command, config.getAgentCode());
        List<String> wrap = platform == SandboxPlatform.LINUX
                ? LinuxBubblewrapCommandBuilder.buildWrapCommand(config, command)
                : buildMacWrapCommand(command);
        return SandboxProcessRunner.run(
                wrap,
                workDirFile,
                config.getTimeoutSeconds(),
                config.getMaxCaptureBytes());
    }

    @Override
    public SandboxExecResult execArgv(List<String> argv) throws IOException {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("argv is empty");
        }
        SandboxCommandFilter.checkArgv(config.toWhitelist(), argv.toArray(new String[0]), config.getAgentCode());
        List<String> wrap = platform == SandboxPlatform.LINUX
                ? LinuxBubblewrapCommandBuilder.buildWrapArgv(config, argv)
                : buildMacWrapArgv(argv);
        return SandboxProcessRunner.run(
                wrap,
                workDirFile,
                config.getTimeoutSeconds(),
                config.getMaxCaptureBytes());
    }

    @Override
    public String getWorkingDirectory() {
        return workDir;
    }

    @Override
    public SandboxConfig getConfig() {
        return config;
    }

    @Override
    public void close() {
        // 无状态沙箱，无需释放资源
    }

    private List<String> buildMacWrapCommand(String command) throws IOException {
        File profileFile = writeSeatbeltProfile();
        String shell = resolveMacShell();
        List<String> cmd = new ArrayList<String>();
        cmd.add("/usr/bin/sandbox-exec");
        cmd.add("-f");
        cmd.add(profileFile.getAbsolutePath());
        cmd.add(shell);
        cmd.add("-c");
        cmd.add(command);
        return cmd;
    }

    private List<String> buildMacWrapArgv(List<String> argv) throws IOException {
        File profileFile = writeSeatbeltProfile();
        List<String> cmd = new ArrayList<String>();
        cmd.add("/usr/bin/sandbox-exec");
        cmd.add("-f");
        cmd.add(profileFile.getAbsolutePath());
        cmd.addAll(argv);
        return cmd;
    }

    private File writeSeatbeltProfile() throws IOException {
        String profile = MacSeatbeltProfileBuilder.build(config);
        File sb = File.createTempFile("team-sandbox-", ".sb");
        Files.write(sb.toPath(), profile.getBytes(StandardCharsets.UTF_8));
        sb.deleteOnExit();
        return sb;
    }
}
