package com.zifang.z.bot.acp;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.ApprovalService;
import com.zifang.z.bot.tool.Sandbox;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AcpTurnTarget} 的生产实现：一层不改语义的 {@link BotAgent} 转发。
 *
 * <p>这里<b>没有</b>第二套审批、第二套会话存储、第二套中断旗子：全部方法都是一行转发到
 * BotAgent 既有公开口（{@code chat}/{@code pendingApprovals}/{@code confirmTool}/
 * {@code stop}/{@code currentSessionId}/{@code listSessions}），行号见
 * {@code _doc/acceptance/p25/EVIDENCE.md} §0.5。</p>
 */
public final class BotAgentAcpTarget implements AcpTurnTarget {

    private final BotAgent agent;

    public BotAgentAcpTarget(BotAgent agent) {
        if (agent == null) {
            throw new IllegalArgumentException("agent 不能为 null");
        }
        this.agent = agent;
    }

    public BotAgent agent() {
        return agent;
    }

    @Override
    public String prompt(String text, StreamListener listener) {
        return agent.chat(text, listener);
    }

    @Override
    public List<ApprovalService.Request> pendingApprovals() {
        return agent.pendingApprovals();
    }

    @Override
    public String confirmTool(String toolName, String argsJson, ApprovalService.Resolution resolution) {
        return agent.confirmTool(toolName, argsJson, resolution);
    }

    @Override
    public void stop() {
        agent.stop();
    }

    @Override
    public String zbotSessionId() {
        return agent.currentSessionId();
    }

    @Override
    public String model() {
        return agent.getModel();
    }

    @Override
    public boolean isRunning() {
        return agent.isRunning();
    }

    @Override
    public void shutdown() {
        agent.shutdown();
    }

    /**
     * 按 profile 装配 BotAgent 的工厂。
     *
     * <p>每个 ACP 会话一个 BotAgent 实例：z-bot 的会话状态（记忆、待批 FIFO、中断旗子）
     * 都挂在实例上，共用一个实例会让两个 IDE 会话串台 —— 那比不实现更糟。</p>
     */
    public static final class Factory implements AcpTurnTarget.Factory {

        private final BotConfig config;
        private final File sandboxDir;

        public Factory(BotConfig config, File sandboxDir) {
            if (config == null) {
                throw new IllegalArgumentException("config 不能为 null");
            }
            this.config = config;
            this.sandboxDir = sandboxDir;
        }

        private BotAgent build(Demand demand) {
            BotAgent.Builder builder = BotAgent.builder(config);
            if (demand != null && demand.model != null && !demand.model.isEmpty()) {
                builder.model(demand.model);
            }
            if (sandboxDir != null) {
                builder.sandbox(new Sandbox(sandboxDir.getAbsolutePath()));
            }
            return builder.build();
        }

        @Override
        public AcpTurnTarget open(Demand demand) {
            BotAgent agent = build(demand);
            if (demand != null && demand.zbotSessionId != null && !demand.zbotSessionId.isEmpty()) {
                agent.switchSession(demand.zbotSessionId);
            } else {
                agent.newSession();
            }
            return new BotAgentAcpTarget(agent);
        }

        /** 配置里的缺省模型：{@code --check} 自拍与 {@code session/set_model} 白名单都用它。 */
        public String defaultModel() {
            return config.getModel();
        }

        @Override
        public List<String> knownZbotSessionIds() {
            BotAgent probe = build(null);
            try {
                List<SessionManager.SessionSummary> summaries = probe.listSessions();
                List<String> ids = new ArrayList<String>();
                if (summaries != null) {
                    for (SessionManager.SessionSummary s : summaries) {
                        if (s != null && s.id != null) {
                            ids.add(s.id);
                        }
                    }
                }
                return ids;
            } finally {
                probe.shutdown();
            }
        }

        @Override
        public void dispose(AcpTurnTarget target) {
            if (target != null) {
                target.shutdown();
            }
        }
    }
}
