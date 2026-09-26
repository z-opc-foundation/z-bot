package com.zifang.z.bot.acp;

import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.tool.ApprovalService;

import java.util.List;

/**
 * ACP 层看到的"一个可对话的 z-bot 会话"。
 *
 * <p>为什么要这层薄接口：{@code agent/BotAgent} 不在本棒写域（工单 §2 禁改），
 * 而 ACP 需要每个 ACP 会话各自一个 agent 实例。本接口只做<b>一行转发</b>的适配
 * （见 {@link BotAgentAcpTarget}），不复制任何 agent 语义 —— 审批状态的真身始终是
 * {@link ApprovalService}，ACP 侧只读队列、只提交决议。</p>
 */
public interface AcpTurnTarget {

    /**
     * 开一个会话的完整诉求。
     *
     * <p>{@code zbotSessionId == null} ⇒ 新会话；非空 ⇒ 采纳已落盘会话。
     * {@code model} 走 {@code BotAgent.Builder.model(String)}（{@code agent/BotAgent.java:1652}，
     * build 时落到 {@code this.model}，{@code :174}），因此换模型是真换、不是改完配置装个样子。
     * {@code execConfirmMode} 本期只透传"配置缺省"：z-bot 的 yolo 语义是
     * <b>启动时冻结、运行期不可改</b>（{@code cli/AgentOptions.java:37—39} 的选项说明原文），
     * 运行期改它需要动 {@code BotAgent}/{@code BuiltinTools}（本棒禁改写域），
     * 故 {@code session/set_mode} 只承认能真空降的那个档，详见 EVIDENCE §未做。</p>
     */
    final class Demand {
        public final String cwd;
        public final String zbotSessionId;
        public final String model;
        public final String execConfirmMode;

        public Demand(String cwd, String zbotSessionId, String model, String execConfirmMode) {
            this.cwd = cwd;
            this.zbotSessionId = zbotSessionId;
            this.model = model;
            this.execConfirmMode = execConfirmMode;
        }

        /** 换一个模型、其余照旧。 */
        public Demand withModel(String newModel) {
            return new Demand(cwd, zbotSessionId, newModel, execConfirmMode);
        }

        public static Demand fresh(String cwd) {
            return new Demand(cwd, null, null, null);
        }

        public static Demand restore(String cwd, String zbotSessionId) {
            return new Demand(cwd, zbotSessionId, null, null);
        }

        @Override
        public String toString() {
            return "Demand(cwd=" + cwd + ", zbotSessionId=" + zbotSessionId + ", model=" + model
                    + ", execConfirmMode=" + execConfirmMode + ")";
        }
    }

    /** 一个 ACP 会话对应一个 z-bot 会话工厂。 */
    interface Factory {
        /** 按诉求开会话（{@link Demand#zbotSessionId} 决定新建还是采纳）。 */
        AcpTurnTarget open(Demand demand);

        /** 落盘可查的 z-bot 会话 id —— "重启后仍能恢复"的唯一凭据。 */
        List<String> knownZbotSessionIds();

        /** 释放（{@code session/close} 与客户断开时调用）。 */
        void dispose(AcpTurnTarget target);
    }

    /**
     * 跑一轮 ReAct。语义同 {@code BotAgent.chat(String, StreamListener)}：
     * 需要人审时返回 {@code WAIT_CONFIRM:tool|args|reason}，被打断时返回「已中止…」。
     */
    String prompt(String text, StreamListener listener);

    /** 当前会话的待批队列快照（FIFO 序）。审批档位由 {@link ApprovalService.Resolution} 决定。 */
    List<ApprovalService.Request> pendingApprovals();

    /** 人（经 IDE）给出决议后放行/拒绝；返回工具输出或拒绝文案。 */
    String confirmTool(String toolName, String argsJson, ApprovalService.Resolution resolution);

    /** 协作式软中断（{@code session/cancel}）。 */
    void stop();

    /** z-bot 侧会话 id（映射的另一端）。 */
    String zbotSessionId();

    /** 当前模型 id（{@code session/set_model} 的回显面）。 */
    String model();

    boolean isRunning();

    void shutdown();
}
