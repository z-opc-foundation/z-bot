package com.zifang.z.bot.slash;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link SlashRegistry} 内置命令单测 — 注册表是终端/HTTP 共用的命令面。
 */
public class SlashRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private BotAgent agent;
    private SlashRegistry registry;

    @Before
    public void setUp() throws Exception {
        agent = BotAgent.builder((com.zifang.z.bot.config.BotConfig) null)
                .provider(new com.zifang.z.agent.kernel.llm.LlmProvider() {
                    @Override
                    public String name() {
                        return "stub";
                    }

                    @Override
                    public java.util.List<com.zifang.z.agent.kernel.llm.Model> listModels() {
                        return java.util.Collections.emptyList();
                    }

                    @Override
                    public boolean supportsModel(String modelId) {
                        return true;
                    }

                    @Override
                    public com.zifang.z.agent.kernel.llm.ChatCompletionsResponse chat(
                            com.zifang.z.agent.kernel.llm.ChatCompletionsRequest request) {
                        throw new UnsupportedOperationException("registry 测试不触发 LLM");
                    }

                    @Override
                    public void streamChat(com.zifang.z.agent.kernel.llm.ChatCompletionsRequest request,
                                           java.util.function.Consumer<com.zifang.z.agent.kernel.llm.ChatCompletionsResponse> onChunk,
                                           java.util.function.Consumer<Throwable> onError) {
                        onError.accept(new UnsupportedOperationException("registry 测试不触发 LLM"));
                    }
                })
                .toolkit(new Toolkit())
                .sandbox(new Sandbox(tmp.newFolder("sb").getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("se")))
                .withoutBuiltinTools()
                .build();
        registry = SlashRegistry.withBuiltinCommands();
    }

    @Test
    public void builtinCommandsAreRegistered() {
        for (String name : new String[]{"/new", "/clear", "/sessions", "/switch", "/tools", "/skills",
                "/sync", "/memory", "/model", "/usage", "/stop", "/steer", "/queue",
                "/compress", "/cron", "/checkpoints", "/rollback", "/background", "/agents"}) {
            assertNotNull("缺少内置命令 " + name, registry.find(name));
        }
        assertEquals("/new", registry.find("/NEW").name());
    }

    /** 重复注册必须炸：/memory 曾注册两次，前一条（center 召回）静默失效无人报警。 */
    @Test(expected = IllegalStateException.class)
    public void duplicateRegistrationIsRejected() {
        registry.register(new SlashCommand() {
            @Override
            public String name() {
                return "/memory";
            }

            @Override
            public String description() {
                return "又一个 /memory";
            }

            @Override
            public String execute(BotAgent agent, String args) {
                return "";
            }
        });
    }

    /** 合并后的 /memory 仍是本地记忆入口（center 召回挂在无参数分支上）。 */
    @Test
    public void memoryCommandStillResolvesToLocalView() {
        String out = registry.find("/memory").execute(agent, "user");
        assertNotNull(out);
        assertTrue(out, !out.contains("又一个"));
    }

    @Test
    public void queueThenUsageReflectsPendingSteer() {
        String out = registry.find("/queue").execute(agent, "晚点处理这个");
        assertTrue(out, out.contains("已排队"));
        assertTrue(agent.context().steer().hasPending());

        String usage = registry.find("/usage").execute(agent, "");
        assertTrue(usage, usage.contains("messages=0"));
    }

    @Test
    public void stopWhenIdleReportsNoRunningTask() {
        String out = registry.find("/stop").execute(agent, "");
        assertEquals("当前没有正在运行的任务", out);
    }

    @Test
    public void steerRequiresArgument() {
        assertEquals("格式: /steer <text>", registry.find("/steer").execute(agent, ""));
    }
}
