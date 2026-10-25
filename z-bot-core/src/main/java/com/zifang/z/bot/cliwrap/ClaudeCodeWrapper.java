package com.zifang.z.bot.cliwrap;

import com.zifang.util.core.lang.RandomUtil;
import com.zifang.z.bot.cliwrap.dto.ChatCompletionRequest;
import com.zifang.z.bot.cliwrap.dto.ChatCompletionResponse;
import com.zifang.z.bot.cliwrap.CliAgentConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Claude Code CLI wrapper：subprocess + stream-json + minimax anthropic 端点。
 *
 * <p>实测铁证：{@code claude -p {prompt} --model MiniMax-M3 --output-format stream-json
 * --verbose --include-partial-messages}，env 注入 {@code ANTHROPIC_BASE_URL=https://api.minimaxi.com/anthropic}
 * + {@code ANTHROPIC_API_KEY}（minimax），产生 {@code stream_event} → {@code event.delta.text}
 * 真流式（14 delta 8.37s→11.74s）。无需 {@code claude /login}。
 *
 * <p>关键：{@code --include-partial-messages} 产生 {@code stream_event} 事件（非 content_block_delta），
 * 由 {@link StreamJsonParser} 的 stream_event 分支解析。
 */
public class ClaudeCodeWrapper implements CliAgentWrapper {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeWrapper.class.getName());
    private static final long PROCESS_TIMEOUT_MS = 10L * 60_000L; // 10 分钟

    @Override
    public boolean supports(String type) {
        return "claude-code".equals(type);
    }

    @Override
    public String streamChat(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut)
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
                String errMsg = "Claude Code process timeout after " + (PROCESS_TIMEOUT_MS / 1000) + "s";
                sseOut.write(ChatCompletionResponse.sseError(errMsg).getBytes(StandardCharsets.UTF_8));
                sseOut.flush();
                return fullReply.toString();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errOutput = readStderr(process);
                String errMsg = "Claude Code exited with code " + exitCode
                        + (errOutput != null && !errOutput.isEmpty() ? ": " + errOutput : "");
                LOG.warning("Claude Code error: " + errMsg);
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

    @Override
    public String syncChat(CliAgentConfig config, ChatCompletionRequest request)
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
                throw new IOException("Claude Code process timeout");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && fullReply.length() == 0) {
                String errOutput = readStderr(process);
                throw new IOException("Claude Code exited with code " + exitCode
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

    // ──────────── 内部方法 ────────────

    /**
     * Spawn claude code 子进程，注入 minimax anthropic 端点环境变量。
     *
     * <p>关键增强（相对 config.defaultArgs）：
     * <ul>
     *   <li>注入 {@code --model MiniMax-M3}（如果 config 未指定）</li>
     *   <li>确保 {@code --include-partial-messages}（真流式必须，产生 stream_event）</li>
     *   <li>注入 {@code ANTHROPIC_BASE_URL} + {@code ANTHROPIC_API_KEY}（minimax，无需 /login）</li>
     * </ul>
     */
    private Process spawnProcess(CliAgentConfig config, String prompt) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();

        String resolvedCommand = resolveCommandPath(config.getCommand());
        List<String> command = new ArrayList<String>();
        command.add(resolvedCommand);

        // 基础参数（来自 config.defaultArgs，已替换 {prompt}）
        List<String> baseArgs = config.resolveArgs(prompt);
        boolean hasModel = false;
        boolean hasIncludePartial = false;
        for (String arg : baseArgs) {
            command.add(arg);
            if ("--model".equals(arg)) {
                hasModel = true;
            }
            if ("--include-partial-messages".equals(arg)) {
                hasIncludePartial = true;
            }
        }

        // 注入 --model MiniMax-M3（如果 config 未指定）
        if (!hasModel) {
            command.add("--model");
            command.add(MinimaxConfigLoader.getDefaultModel());
        }

        // 确保 --include-partial-messages（真流式必须，产生 stream_event）
        if (!hasIncludePartial) {
            command.add("--include-partial-messages");
        }

        pb.command(command);

        // 注入 minimax anthropic 端点环境变量
        Map<String, String> env = pb.environment();
        String apiKey = MinimaxConfigLoader.loadApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            env.put("ANTHROPIC_BASE_URL", MinimaxConfigLoader.getBaseUrl());
            env.put("ANTHROPIC_API_KEY", apiKey);
            LOG.info("Injected ANTHROPIC_BASE_URL=" + MinimaxConfigLoader.getBaseUrl()
                    + " ANTHROPIC_API_KEY=" + apiKey.substring(0, Math.min(15, apiKey.length())) + "...");
        } else {
            LOG.warning("Minimax API key not found in ~/.hermes/config.yaml, claude code may fail without /login");
        }

        // 增强 PATH（GUI 应用启动的 JAR 进程 PATH 不含用户 bin 目录）
        enhancePath(pb);

        // 工作目录
        if (config.getWorkingDir() != null && !config.getWorkingDir().isEmpty()) {
            String dir = config.getWorkingDir().replace("~", System.getProperty("user.home"));
            File workDir = new File(dir);
            if (!workDir.exists()) {
                workDir.mkdirs();
            }
            pb.directory(workDir);
        }

        pb.redirectErrorStream(false);
        LOG.info("Spawning Claude Code: command=" + command);

        return pb.start();
    }

    /**
     * 增强 PATH 环境变量，确保子进程能找到 nvm 管理的 node bin 等用户工具。
     */
    private void enhancePath(ProcessBuilder pb) {
        String currentPath = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
        String home = System.getProperty("user.home");
        List<String> extraPathList = new ArrayList<String>();
        extraPathList.add(home + "/.local/bin");
        extraPathList.add("/usr/local/bin");
        extraPathList.add("/opt/homebrew/bin");
        extraPathList.add("/opt/homebrew/sbin");
        // nvm 管理的 node bin 目录（claude 是 npm 全局工具）
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
    }

    /**
     * 将命令名解析为绝对路径。macOS GUI 进程 PATH 精简，需在常见 bin 目录查找。
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
                LOG.info("Resolved claude command '" + command + "' -> " + file.getAbsolutePath());
                return file.getAbsolutePath();
            }
        }

        LOG.warning("Could not resolve claude command '" + command + "' in common bin directories, using as-is");
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
     * 读取 stderr 输出（用于错误诊断）。
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
