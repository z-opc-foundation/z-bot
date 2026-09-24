package com.zifang.z.bot.channel;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.center.BotCenterClient;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.slash.SlashCommand;
import com.zifang.z.bot.slash.SlashRegistry;
import com.zifang.z.bot.ui.MarkdownRenderer;
import com.zifang.z.bot.ui.RawTerminalReader;
import com.zifang.z.bot.ui.TerminalUI;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 终端通道 — Claude Code / Hermes 风格 REPL。
 *
 * <p>{@link BotAgent#chat(String, StreamListener)} 在调用线程上同步派发事件，
 * 所以这里不需要响应式流：收到什么就画什么，spinner 在第一个事件到来时停掉。</p>
 *
 * <p>输入走 {@link RawTerminalReader}：Unix TTY 下是 raw 模式（Tab 补全斜杠命令），
 * 管道 / 非 Unix 环境自动降级成行模式。</p>
 */
public final class TerminalChannel {

    private final BotAgent agent;
    /** 与 HTTP 通道共享的斜杠命令注册表；终端私有命令（/theme /confirm /exit…）不进表。 */
    private final SlashRegistry slash = SlashRegistry.withBuiltinCommands();
    private final AtomicInteger stepCount = new AtomicInteger(0);
    private final AtomicInteger tokenEstimate = new AtomicInteger(0);

    private volatile boolean running = true;
    private volatile String currentTheme = "cyan";
    private volatile String lastReply = "";
    private volatile PendingConfirm pendingConfirm;
    private long chatStartMs;

    public TerminalChannel(BotAgent agent) {
        this.agent = agent;
    }

    public void run() throws IOException {
        TerminalUI.printWelcome();
        renderStartupPanel();
        TerminalUI.printCommandTable();

        try (RawTerminalReader reader = new RawTerminalReader()) {
            while (running) {
                TerminalUI.prompt();
                String input = reader.readLine();
                if (input == null) {
                    break;
                }
                String trimmed = input.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("/")) {
                    handleSlashCommand(trimmed);
                } else {
                    streamReply(trimmed);
                }
            }
        }
        agent.shutdown();
    }

    public void stop() {
        running = false;
    }

    // ===== 对话渲染 =====

    private void streamReply(String input) {
        lastReply = "";
        chatStartMs = System.currentTimeMillis();
        stepCount.set(0);
        tokenEstimate.set(0);
        TerminalUI.thinkingHeader();
        Thread spinner = TerminalUI.startSpinner("thinking", System.out);

        String reply;
        try {
            reply = agent.chat(input, new StreamListener() {
                @Override
                public void onEvent(StreamEvent event) {
                    render(event, spinner);
                }
            });
        } catch (RuntimeException e) {
            stopSpinner(spinner);
            TerminalUI.error(e.getMessage());
            System.out.println();
            return;
        }

        stopSpinner(spinner);
        if (reply != null && reply.startsWith(BotAgent.WAIT_CONFIRM_PREFIX)) {
            showPendingConfirm(reply);
        } else {
            lastReply = reply == null ? "" : reply;
        }
        System.out.println();
    }

    private void render(StreamEvent event, Thread spinner) {
        if (event instanceof StreamEvent.StepStart) {
            int n = stepCount.incrementAndGet();
            TerminalUI.printStatusBar(agent.getModel(), "step " + n, n, tokenEstimate.get());
        } else if (event instanceof StreamEvent.ThoughtDelta) {
            stopSpinner(spinner);
            String text = ((StreamEvent.ThoughtDelta) event).text;
            tokenEstimate.addAndGet(estimateTokens(text));
            TerminalUI.plain(TerminalUI.DIM_GRAY + "  " + text.replace("\n", "\n  ") + TerminalUI.RESET);
        } else if (event instanceof StreamEvent.ToolCallRequest) {
            stopSpinner(spinner);
            StreamEvent.ToolCallRequest tc = (StreamEvent.ToolCallRequest) event;
            TerminalUI.toolCall(tc.name, tc.argumentsJson);
        } else if (event instanceof StreamEvent.ToolResult) {
            StreamEvent.ToolResult tr = (StreamEvent.ToolResult) event;
            TerminalUI.toolResult(tr.success ? String.valueOf(tr.result) : tr.error, tr.success);
        } else if (event instanceof StreamEvent.FinalDelta) {
            stopSpinner(spinner);
            String text = ((StreamEvent.FinalDelta) event).text;
            tokenEstimate.addAndGet(estimateTokens(text));
            System.out.print(TerminalUI.PRIMARY + "● " + TerminalUI.RESET);
            System.out.print(MarkdownRenderer.render(text));
            System.out.flush();
        } else if (event instanceof StreamEvent.Done) {
            StreamEvent.Done d = (StreamEvent.Done) event;
            int tokens = d.completionTokens == null ? tokenEstimate.get() : d.completionTokens;
            TerminalUI.done(d.totalSteps, tokens, System.currentTimeMillis() - chatStartMs);
            lastReply = d.reply;
        } else if (event instanceof StreamEvent.ErrorEvent) {
            stopSpinner(spinner);
            TerminalUI.error(((StreamEvent.ErrorEvent) event).cause.getMessage());
        }
    }

    private void showPendingConfirm(String reply) {
        String[] parts = reply.substring(BotAgent.WAIT_CONFIRM_PREFIX.length()).split("\\|", 3);
        if (parts.length < 3) {
            TerminalUI.error("无法解析的确认请求: " + reply);
            return;
        }
        pendingConfirm = new PendingConfirm(parts[0], parts[1], parts[2]);
        TerminalUI.warn("危险命令等待确认");
        TerminalUI.info("工具: " + TerminalUI.BOLD + parts[0] + TerminalUI.RESET);
        TerminalUI.info("参数: " + parts[1]);
        TerminalUI.info("执行 " + TerminalUI.PRIMARY + "/confirm" + TerminalUI.RESET
                + " 确认 / " + TerminalUI.DIM_GRAY + "忽略" + TerminalUI.RESET);
        System.out.println();
    }

    // ===== 斜杠命令 =====

    private void handleSlashCommand(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String name = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        SlashCommand command = slash.find(name);
        if (command != null) {
            TerminalUI.plain(MarkdownRenderer.render(command.execute(agent, args)));
        } else if ("/status".equals(name)) {
            renderStartupPanel();
        } else if ("/theme".equals(name)) {
            cmdTheme(args);
        } else if ("/feedback".equals(name)) {
            cmdFeedback(args);
        } else if ("/confirm".equals(name)) {
            cmdConfirm();
        } else if ("/help".equals(name) || "/?".equals(name)) {
            TerminalUI.printCommandTable();
        } else if ("/exit".equals(name) || "/quit".equals(name) || "/q".equals(name)) {
            TerminalUI.info("Bye~");
            running = false;
        } else {
            TerminalUI.error("未知命令: " + name + "  — 输入 /help 查看可用命令");
        }
        System.out.println();
    }

    private void cmdFeedback(String args) {
        if (lastReply.isEmpty()) {
            TerminalUI.warn("还没有 chat 过，无法评分");
            return;
        }
        Integer rating = parseRating(args);
        if (rating == null) {
            TerminalUI.error("格式: /feedback up | down | <int>");
            return;
        }
        String appCode = agent.getConfig() == null ? "default-chat" : agent.getConfig().getAppCode();
        agent.submitFeedback(rating, "", appCode, null);
        TerminalUI.success("反馈已提交: " + (rating > 0 ? "👍 赞" : rating < 0 ? "👎 踩" : "😐 中性"));
    }

    private void cmdTheme(String args) {
        if (args.isEmpty()) {
            TerminalUI.info("当前主题: " + currentTheme + "  — 切换: /theme cyan|green|amber");
        } else {
            currentTheme = args;
            TerminalUI.success("主题切换到: " + currentTheme + "（重启后生效）");
        }
    }

    private void cmdConfirm() {
        if (pendingConfirm == null) {
            TerminalUI.warn("没有待确认的危险命令");
            return;
        }
        String result = agent.confirmTool(pendingConfirm.toolName, pendingConfirm.argsJson);
        TerminalUI.success("已确认 [" + pendingConfirm.toolName + "]");
        String r = result == null ? "" : result;
        TerminalUI.plain("  " + (r.length() > 500 ? r.substring(0, 500) + "..." : r));
        pendingConfirm = null;
    }

    // ===== 状态面板 =====

    private void renderStartupPanel() {
        BotCenterClient c = agent.getCenterClient();
        TerminalUI.printStatusPanel(agent.getModel(), agent.getToolkit().size(),
                agent.listInstalledSkills().size(), agent.getInstanceCode(),
                c == null ? TerminalUI.DIM_GRAY + "(local-only, no center)" + TerminalUI.RESET
                        : c.isEnabled() ? TerminalUI.SUCCESS + "connected" + TerminalUI.RESET
                        : TerminalUI.DIM_GRAY + "(disabled)" + TerminalUI.RESET);
    }

    private static Integer parseRating(String args) {
        if (args.isEmpty()) {
            return 0;
        }
        if (args.startsWith("up") || "+1".equals(args) || "1".equals(args) || "good".equals(args)) {
            return 1;
        }
        if (args.startsWith("down") || "-1".equals(args) || "bad".equals(args)) {
            return -1;
        }
        try {
            return Integer.parseInt(args);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void stopSpinner(Thread t) {
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(50);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static int estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : Math.max(1, text.length() / 4);
    }

    private static final class PendingConfirm {
        final String toolName;
        final String argsJson;
        final String reason;

        PendingConfirm(String toolName, String argsJson, String reason) {
            this.toolName = toolName;
            this.argsJson = argsJson;
            this.reason = reason;
        }
    }

}
