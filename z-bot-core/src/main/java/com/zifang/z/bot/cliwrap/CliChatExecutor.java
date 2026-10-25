package com.zifang.z.bot.cliwrap;

import com.zifang.util.core.lang.RandomUtil;
import com.zifang.z.bot.cliwrap.dto.ChatCompletionRequest;
import com.zifang.z.bot.cliwrap.dto.ChatCompletionResponse;
import com.zifang.z.bot.cliwrap.CliAgentConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * CLI Agent 聊天执行器：委托 {@link CliAgentWrapperRegistry} 路由到具体 wrapper。
 *
 * <p>优先委托 registry：
 * <ul>
 *   <li>{@link ClaudeCodeWrapper} — subprocess + stream_event（claude-code）</li>
 *   <li>{@link HermesProcessWrapper} — subprocess（hermes）</li>
 * </ul>
 *
 * <p>registry 为空或 type 无匹配时，回退到原始 spawn 逻辑（兼容未知 CLI type）。
 * 回退路径使用 {@link StreamJsonParser} 解析（支持 stream_event 等多种格式）。
 *
 * <p>从 z-team-agent-core 迁入 wrapper 模块。保留 public API（executeStream/executeSync）
 * 不变，starter 仅需改 import + 注入 registry。
 */
public class CliChatExecutor {

    private static final Logger LOG = Logger.getLogger(CliChatExecutor.class.getName());
    private static final long PROCESS_TIMEOUT_MS = 10L * 60_000L; // 10 分钟

    private volatile CliAgentWrapperRegistry registry;

    public CliChatExecutor() {
    }

    /**
     * 注入 wrapper 注册表。注入后，executeStream/executeSync 优先委托 registry。
     */
    public void setRegistry(CliAgentWrapperRegistry registry) {
        this.registry = registry;
        LOG.info("CliChatExecutor registry set: " + (registry != null ? "active" : "null"));
    }

    /**
     * 流式执行：优先委托 registry，回退 spawn。
     */
    public String executeStream(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut)
            throws IOException {
        // 优先委托 registry
        if (registry != null) {
            CliAgentWrapper wrapper = registry.get(config.getType());
            if (wrapper != null) {
                LOG.info("Delegating stream to " + wrapper.getClass().getSimpleName()
                        + " for type=" + config.getType());
                return wrapper.streamChat(config, request, sseOut);
            }
        }
        // 回退原 spawn 逻辑
        LOG.info("No wrapper for type=" + config.getType() + ", fallback to spawn");
        return fallbackStream(config, request, sseOut);
    }

    /**
     * 非流式执行：优先委托 registry，回退 spawn。
     */
    public String executeSync(CliAgentConfig config, ChatCompletionRequest request)
            throws IOException {
        if (registry != null) {
            CliAgentWrapper wrapper = registry.get(config.getType());
            if (wrapper != null) {
                LOG.info("Delegating sync to " + wrapper.getClass().getSimpleName()
                        + " for type=" + config.getType());
                return wrapper.syncChat(config, request);
            }
        }
        LOG.info("No wrapper for type=" + config.getType() + ", fallback to spawn");
        return fallbackSync(config, request);
    }

    // ──────────── 回退 spawn 逻辑（兼容未知 type） ────────────

    /**
     * 回退流式：spawn CLI 进程，读 stdout 逐行解析为 SSE。
     */
    private String fallbackStream(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut)
            throws IOException {
        String prompt = buildPrompt(request);
        String completionId = "chatcmpl-cli-" + RandomUtil.uuidCompact();
        long created = System.currentTimeMillis() / 1000L;
        String model = config.getType();

        Process process = spawnProcess(config, prompt);
        StringBuilder fullReply = new StringBuilder();

        try {
            InputStream stdout = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                String delta = StreamJsonParser.extractDelta(line);
                if (delta != null && !delta.isEmpty()) {
                    fullReply.append(delta);
                    String sseChunk = ChatCompletionResponse.sseChunk(completionId, model, created, delta);
                    sseOut.write(sseChunk.getBytes(StandardCharsets.UTF_8));
                    sseOut.flush();
                }
            }

            boolean exited = process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                String errMsg = "CLI process timeout after " + (PROCESS_TIMEOUT_MS / 1000) + "s";
                sseOut.write(ChatCompletionResponse.sseError(errMsg).getBytes(StandardCharsets.UTF_8));
                sseOut.flush();
                return fullReply.toString();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errOutput = readStderr(process);
                String errMsg = "CLI process exited with code " + exitCode
                        + (errOutput != null && !errOutput.isEmpty() ? ": " + errOutput : "");
                LOG.warning("CLI agent error: " + errMsg);
                if (fullReply.length() == 0) {
                    sseOut.write(ChatCompletionResponse.sseError(errMsg).getBytes(StandardCharsets.UTF_8));
                    sseOut.flush();
                }
            }

            sseOut.write(ChatCompletionResponse.sseDone().getBytes(StandardCharsets.UTF_8));
            sseOut.flush();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sseOut.write(ChatCompletionResponse.sseError("Interrupted").getBytes(StandardCharsets.UTF_8));
            sseOut.flush();
        } finally {
            process.destroyForcibly();
        }

        return fullReply.toString();
    }

    /**
     * 回退非流式：spawn CLI 进程，聚合返回。
     */
    private String fallbackSync(CliAgentConfig config, ChatCompletionRequest request)
            throws IOException {
        String prompt = buildPrompt(request);
        Process process = spawnProcess(config, prompt);
        StringBuilder fullReply = new StringBuilder();

        try {
            InputStream stdout = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                String delta = StreamJsonParser.extractDelta(line);
                if (delta != null && !delta.isEmpty()) {
                    fullReply.append(delta);
                }
            }

            boolean exited = process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new IOException("CLI process timeout");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && fullReply.length() == 0) {
                String errOutput = readStderr(process);
                throw new IOException("CLI process exited with code " + exitCode
                        + (errOutput != null && !errOutput.isEmpty() ? ": " + errOutput : ""));
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            process.destroyForcibly();
        }

        return fullReply.toString();
    }

    // ──────────── spawn 工具方法（回退路径用） ────────────

    /**
     * Spawn CLI 子进程。
     */
    private Process spawnProcess(CliAgentConfig config, String prompt) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();

        String resolvedCommand = resolveCommandPath(config.getCommand());
        pb.command().add(resolvedCommand);
        for (String arg : config.resolveArgs(prompt)) {
            pb.command().add(arg);
        }

        // 增强 PATH
        String currentPath = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
        String home = System.getProperty("user.home");
        List<String> extraPathList = new ArrayList<String>();
        extraPathList.add(home + "/.local/bin");
        extraPathList.add("/usr/local/bin");
        extraPathList.add("/opt/homebrew/bin");
        extraPathList.add("/opt/homebrew/sbin");
        File nvmNodeDir = new File(home + "/.nvm/versions/node");
        if (nvmNodeDir.isDirectory()) {
            File[] versions = nvmNodeDir.listFiles();
            if (versions != null) {
                for (File v : versions) {
                    extraPathList.add(v.getAbsolutePath() + "/bin");
                }
            }
        }
        StringBuilder enhancedPath = new StringBuilder(currentPath);
        for (String extra : extraPathList) {
            if (!currentPath.contains(extra)) {
                enhancedPath.insert(0, extra + ":");
            }
        }
        pb.environment().put("PATH", enhancedPath.toString());

        if (config.getWorkingDir() != null && !config.getWorkingDir().isEmpty()) {
            String dir = config.getWorkingDir().replace("~", System.getProperty("user.home"));
            File workDir = new File(dir);
            if (!workDir.exists()) {
                workDir.mkdirs();
            }
            pb.directory(workDir);
        }

        pb.redirectErrorStream(false);
        LOG.info("Spawning CLI agent (fallback): command=" + resolvedCommand
                + " args=" + config.resolveArgs(prompt).size() + " type=" + config.getType());

        return pb.start();
    }

    /**
     * 将命令名解析为绝对路径。
     */
    private String resolveCommandPath(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        if (command.startsWith("/")) {
            return command;
        }

        String home = System.getProperty("user.home");
        List<String> searchPaths = new ArrayList<String>();
        searchPaths.add(home + "/.local/bin");
        searchPaths.add("/usr/local/bin");
        searchPaths.add("/opt/homebrew/bin");
        searchPaths.add("/usr/bin");
        searchPaths.add("/bin");
        File nvmNodeDir = new File(home + "/.nvm/versions/node");
        if (nvmNodeDir.isDirectory()) {
            File[] versions = nvmNodeDir.listFiles();
            if (versions != null) {
                for (File v : versions) {
                    searchPaths.add(v.getAbsolutePath() + "/bin");
                }
            }
        }

        for (String dir : searchPaths) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute()) {
                LOG.info("Resolved CLI command '" + command + "' -> " + file.getAbsolutePath());
                return file.getAbsolutePath();
            }
        }

        LOG.warning("Could not resolve CLI command '" + command + "' in common bin directories, using as-is");
        return command;
    }

    /**
     * 从 messages 数组构建 prompt 字符串。
     */
    private String buildPrompt(ChatCompletionRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatCompletionRequest.ChatMessage msg : request.getMessages()) {
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            String role = msg.getRole();
            if (role == null) {
                role = "user";
            }
            if ("system".equalsIgnoreCase(role)) {
                sb.append("[System Instructions]\n").append(msg.getContent()).append("\n\n");
            } else if ("assistant".equalsIgnoreCase(role)) {
                sb.append("[Assistant]\n").append(msg.getContent()).append("\n\n");
            } else {
                sb.append("[User]\n").append(msg.getContent()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 读取 stderr 输出。
     */
    private String readStderr(Process process) {
        try {
            InputStream stderr = process.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "";
        }
    }
}
