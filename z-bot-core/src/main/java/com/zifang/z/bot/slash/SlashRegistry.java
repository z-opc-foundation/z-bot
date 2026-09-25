package com.zifang.z.bot.slash;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.center.BotCenterClient;
import com.zifang.z.bot.session.SessionManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 斜杠命令注册表 — 单份实现被终端通道与 HTTP 通道共享（对齐 hermes 的 82 个 slash 命令面）。
 *
 * <p>通道特有、需要私有的 UI 状态的命令（如终端的 /theme /confirm /exit）不进注册表，
 * 由各通道自己处理；注册表只收"纯 agent 操作"类命令。</p>
 */
public final class SlashRegistry {

    private final Map<String, SlashCommand> commands = new LinkedHashMap<String, SlashCommand>();

    /**
     * 注册命令；同名重复直接拒绝。
     *
     * <p>这里曾经是 {@code put} 覆盖，于是 {@code /memory} 被注册两次（center 召回 + 本地记忆）
     * 时前一条静默失效且无人报错 — 命令表看不出问题，行为却少了一半。</p>
     */
    public SlashRegistry register(SlashCommand command) {
        String key = command.name().toLowerCase();
        if (commands.containsKey(key)) {
            throw new IllegalStateException("斜杠命令重复注册: " + command.name());
        }
        commands.put(key, command);
        return this;
    }

    public SlashCommand find(String name) {
        return name == null ? null : commands.get(name.toLowerCase());
    }

    public boolean handles(String name) {
        return find(name) != null;
    }

    public Collection<SlashCommand> all() {
        return Collections.unmodifiableCollection(commands.values());
    }

    /**
     * 注册内置 agent 命令：/new /clear /sessions /switch /tools /skills /sync /memory
     * /model /usage /stop /steer /queue。
     */
    public static SlashRegistry withBuiltinCommands() {
        SlashRegistry r = new SlashRegistry();
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/new";
            }

            @Override
            public String description() {
                return "新建会话";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return "新会话已创建: " + agent.newSession();
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/clear";
            }

            @Override
            public String description() {
                return "清空当前会话记忆";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                agent.clearMemory();
                return "记忆已清空";
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/sessions";
            }

            @Override
            public String description() {
                return "列出本地会话";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                List<SessionManager.SessionSummary> sessions = agent.listSessions();
                if (sessions.isEmpty()) {
                    return "本地暂无会话";
                }
                String current = agent.currentSessionId();
                StringBuilder sb = new StringBuilder("本地会话 (" + sessions.size() + ")\n");
                for (SessionManager.SessionSummary s : sessions) {
                    sb.append(s.id.equals(current) ? "* " : "  ")
                            .append(s.id).append("  ").append(s.messageCount).append(" msgs  ")
                            .append(s.title).append('\n');
                }
                return sb.toString().trim();
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/switch";
            }

            @Override
            public String description() {
                return "切换会话: /switch <sessionId>";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                if (args.isEmpty()) {
                    return "格式: /switch <sessionId>（先 /sessions 查看）";
                }
                agent.switchSession(args);
                return "已切换到会话 " + args + "（" + agent.getMemory().size() + " 条消息）";
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/tools";
            }

            @Override
            public String description() {
                return "列出已注册工具";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                StringBuilder sb = new StringBuilder("已注册工具 (" + agent.getToolkit().size() + ")\n");
                for (String name : agent.getToolkit().getToolNames()) {
                    sb.append("- ").append(name).append('\n');
                }
                return sb.toString().trim();
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/skills";
            }

            @Override
            public String description() {
                return "已安装 Skill: /skills 列表, /skills view <name> 查看内容";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return agent.skillsManage(args);
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/sync";
            }

            @Override
            public String description() {
                return "从 z-agent-center 同步 Skill";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                BotCenterClient c = agent.getCenterClient();
                if (c == null || !c.isEnabled()) {
                    return "Bot 未接入 z-agent-center，无法 sync";
                }
                int wrote = agent.syncSkillsFromCenter();
                return "同步完成：写入了 " + wrote + " 个 SKILL.md 到本地";
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/model";
            }

            @Override
            public String description() {
                return "显示当前模型";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return "model: " + agent.getProviderCode() + " / " + agent.getModel();
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/usage";
            }

            @Override
            public String description() {
                return "本轮运行用量（api 调用 / tokens / 消息数）";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return "apiCalls=" + agent.context().budget().apiCalls()
                        + "  tokens=" + agent.context().budget().tokensUsed()
                        + "  messages=" + agent.getMemory().size()
                        + "  running=" + agent.isRunning();
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/stop";
            }

            @Override
            public String description() {
                return "请求协作式中断，在最近的迭代/工具边界生效";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                if (!agent.isRunning()) {
                    return "当前没有正在运行的任务";
                }
                agent.stop();
                return "已请求停止，将在最近的迭代边界生效";
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/steer";
            }

            @Override
            public String description() {
                return "运行中插话: /steer <text>，工具间隙注入 [User steer]";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                if (args.isEmpty()) {
                    return "格式: /steer <text>";
                }
                agent.steer(args);
                return agent.isRunning()
                        ? "已入队 steer，将在工具间隙注入"
                        : "已入队，将在下次对话开头并入";
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/queue";
            }

            @Override
            public String description() {
                return "排队消息: /queue <text>，下次对话开头并入 [User queued]";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                if (args.isEmpty()) {
                    return "格式: /queue <text>";
                }
                agent.steer(args);
                return "已排队，将在下次对话开头并入";
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/compress";
            }

            @Override
            public String description() {
                return "上下文压缩: /compress 查看状态并执行, /compress preview 只看状态";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return agent.compressNow(args);
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/memory";
            }

            @Override
            public String description() {
                return "本地记忆: /memory 查看, /memory user, /memory pending, /memory forget [user]";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                String local = agent.memoryManage(args);
                if (!args.isEmpty()) {
                    return local;
                }
                // 无参数时顺带召回 center 侧长期记忆（历史上它是另一条 /memory，被重复注册静默吞掉）
                BotCenterClient c = agent.getCenterClient();
                if (c == null || !c.isEnabled()) {
                    return local;
                }
                String recalled = c.recallMemory();
                return recalled == null || recalled.isEmpty()
                        ? local + "\n\ncenter 暂无长期记忆（首轮 chat 后会自动积累）"
                        : local + "\n\n[center 长期记忆]\n" + recalled;
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/cron";
            }

            @Override
            public String description() {
                return "定时任务: /cron 列表, /cron add <schedule> | <name> | <prompt>, remove/pause/resume <id>";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return agent.cronManage(args);
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/checkpoints";
            }

            @Override
            public String description() {
                return "沙箱快照列表: /checkpoints, /checkpoints prune [n] 修剪";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return agent.checkpointManage(args);
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/rollback";
            }

            @Override
            public String description() {
                return "回滚沙箱到快照: /rollback [id]（缺省最近一次）";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return agent.rollbackCheckpoint(args);
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/background";
            }

            @Override
            public String description() {
                return "异步委托子代理: /background <task>，/background result <id> 取回结果";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                if (args != null && args.toLowerCase().startsWith("result")) {
                    return agent.backgroundResult(args.substring(6).trim());
                }
                if (args == null || args.trim().isEmpty()) {
                    return "格式: /background <task> 或 /background result <id>";
                }
                return agent.submitBackground(args);
            }
        });
        r.register(new SlashCommand() {
            @Override
            public String name() {
                return "/agents";
            }

            @Override
            public String description() {
                return "查看异步委托台账（在跑/已完成/失败的子代理）";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return agent.describeAgents();
            }
        });
        return r;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }
        return sb.toString();
    }

    /** 全部命令的 "name — description" 清单（/help 用）。 */
    public List<String> describeAll() {
        List<String> out = new ArrayList<String>();
        for (SlashCommand c : commands.values()) {
            out.add(c.name() + " — " + c.description());
        }
        return out;
    }
}
