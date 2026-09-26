package com.zifang.z.bot.cli;

import com.zifang.z.bot.acp.AcpApprovalBridge;
import com.zifang.z.bot.acp.AcpAgentServer;
import com.zifang.z.bot.acp.AcpConnection;
import com.zifang.z.bot.acp.AcpSessionRegistry;
import com.zifang.z.bot.acp.BotAgentAcpTarget;
import com.zifang.z.bot.acp.StdioAcpTransport;
import com.zifang.z.bot.config.BotConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code z-bot acp} —— 把 z-bot 当 ACP（Agent Client Protocol）agent 挂到编辑器/客户端上。
 *
 * <p>传输是 <b>stdio</b>：stdin 收帧、stdout 回帧，日志一律 stderr（stdout 只能放协议，
 * 这是 ACP 客户端能解析我们的前提）。<b>不开任何端口</b>（工单 §4 红线 2 的首选形态）。</p>
 *
 * <p>用法（Zed / acp-bridge 那类客户端的启动命令）：</p>
 * <pre>
 *   z-bot acp                        # 用 ~/.zbot 这个 profile
 *   z-bot acp --config-dir DIR       # 换 profile（E2E / 多实例）
 *   z-bot acp --model minimax/MiniMax-Text-01
 * </pre>
 *
 * <p>{@code --check} 是"不接 stdin 也想知道装配对不对"的自检：在同一套对象上跑一遍
 * {@code initialize → session/new → session/list → session/close} 的内存对拍，
 * 把每一帧打到 stderr，退出码非 0 表示这一套接不起来。它存在的理由：真 IDE 里排错成本高。</p>
 */
@Command(name = "acp", description = "以 ACP（Agent Client Protocol）在 stdio 上作为 agent 运行（供 IDE/编辑器接入）")
public class AcpCommand implements Callable<Integer> {

    @Mixin
    AgentOptions options = new AgentOptions();

    @Option(names = {"--check"}, description = "只做一次内存自拍（initialize/newSession/list/close），不接 stdin")
    boolean check;

    @Option(names = {"--allow-always"}, description = "在审批外送里给出 allow_always 档（会落盘 config.properties）")
    boolean allowAlways;

    @Option(names = {"--model-catalog"}, paramLabel = "IDS", split = ",",
            description = "session/set_model 可接受的 modelId 白名单（逗号分隔；不给则只允许当前模型）")
    List<String> modelCatalog;

    @Override
    public Integer call() {
        BotConfig config;
        try {
            config = options.loadConfig();
        } catch (RuntimeException e) {
            System.err.println("[acp] 配置读不出来: " + e);
            return 2;
        }
        File sandboxDir = options.sandboxDir(config);
        AcpSessionRegistry registry = null;
        try {
            BotAgentAcpTarget.Factory factory =
                    new BotAgentAcpTarget.Factory(config, sandboxDir);
            registry = new AcpSessionRegistry(factory);
            StdioAcpTransport transport = new StdioAcpTransport(System.out);
            AcpConnection connection = new AcpConnection(transport);
            new AcpAgentServer(connection, registry,
                    new AcpApprovalBridge(), authMethods(config), Arrays.asList("default"),
                    models(config), allowAlways).register();

            if (check) {
                return selfCheck(factory);
            }
            System.err.println("[acp] z-bot ACP agent 就绪：stdio、profile="
                    + (config.getConfigDir() == null ? "~/.zbot" : config.getConfigDir().getPath())
                    + "、模型=" + config.getModel()
                    + "（Ctrl-C / 关闭 stdin 退出）");
            connection.serveStdio(System.in);
            System.err.println("[acp] stdin EOF，退出");
            return 0;
        } catch (RuntimeException e) {
            System.err.println("[acp] 起不来: " + e);
            return 1;
        } finally {
            if (registry != null) {
                registry.closeAll();
            }
        }
    }

    /** 认证方式：配置里配了 key 的 provider code + 一个通用的 {@code z-bot-config}。 */
    private static List<String> authMethods(BotConfig config) {
        Set<String> ids = new LinkedHashSet<String>();
        ids.add("z-bot-config");
        try {
            BotConfig.Provider active = config.activeProvider();
            if (active != null && active.getCode() != null) {
                ids.add(active.getCode());
            }
        } catch (RuntimeException ignored) {
            // profile 没配 provider 时只留 z-bot-config，不影响协议面。
        }
        return new ArrayList<String>(ids);
    }

    private List<String> models(BotConfig config) {
        List<String> ids = new ArrayList<String>();
        if (modelCatalog != null) {
            for (String id : modelCatalog) {
                if (id != null && !id.trim().isEmpty()) {
                    ids.add(id.trim());
                }
            }
        }
        String current = config.getModel();
        if (current != null && !ids.contains(current)) {
            ids.add(current);
        }
        return ids;
    }

    /**
     * 内存自拍：真装配（同一个 {@link BotAgentAcpTarget.Factory}、同一个 bridge、同一个
     * {@link AcpAgentServer}），只是不走管道。用来回答"这套接线到底起不起得来"，
     * 不必先起 IDE。
     *
     * @return 0 全通；非 0 = 失败的那一步序号（stderr 里有帧原文）
     */
    private int selfCheck(BotAgentAcpTarget.Factory factory) {
        final List<String> frames = new ArrayList<String>();
        AcpConnection probe = new AcpConnection(new com.zifang.z.bot.acp.AcpTransport() {
            @Override
            public void sendLine(String jsonLine) {
                synchronized (frames) {
                    frames.add(jsonLine);
                }
            }

            @Override
            public void close() {
            }
        }, System.err);
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        new AcpAgentServer(probe, registry, new AcpApprovalBridge(),
                Arrays.asList("z-bot-config"), Arrays.asList("default"),
                Arrays.asList(factory.defaultModel())).register();

        String[] chain = {
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                        + "\"protocolVersion\":1,\"clientInfo\":{\"name\":\"z-bot --check\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/nope\",\"params\":{}}"
        };
        for (int i = 0; i < chain.length; i++) {
            probe.handleLine(chain[i]);
            List<String> emitted;
            synchronized (frames) {
                // 必须先取快照再清空：清空之后再问 isEmpty() 恒为真，
                // 这条自检就会在"其实全接上了"的那次运行里也返回非 0（rc=i+1）。
                emitted = new ArrayList<String>(frames);
                frames.clear();
            }
            for (String f : emitted) {
                System.err.println("[acp --check] ← " + f);
            }
            if (emitted.isEmpty()) {
                System.err.println("[acp --check] 第 " + (i + 1) + " 步没有任何回帧: " + chain[i]);
                return i + 1;
            }
            if (i == chain.length - 1) {
                // 注释里承诺的自检点要真验：未知方法必须大声 -32601，
                // 否则"接得上"可能只是"什么都往里吞"。
                boolean methodNotFound = false;
                for (String f : emitted) {
                    if (f.contains("\"code\":-32601")) {
                        methodNotFound = true;
                    }
                }
                if (!methodNotFound) {
                    System.err.println("[acp --check] 未知方法没有按 -32601 大声失败: " + emitted);
                    return chain.length + 1;
                }
            }
        }
        registry.closeAll();
        System.err.println("[acp --check] 装配自检通过：initialize/newSession/list 有回帧，"
                + "未知方法已按 -32601 大声失败");
        return 0;
    }
}
