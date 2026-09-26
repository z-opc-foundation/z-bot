package com.zifang.z.bot.skill;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.slash.SlashRegistry;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * P23c / D-1：<b>生产接线层</b>的守卫 —— 真 {@link SlashRegistry#withBuiltinCommands()}
 * 经 {@link BotAgent#skillCommandPlan(List)} 这条真链跑出来的命令表账本。
 *
 * <p>这条链从前是<b>零覆盖</b>的：两处单测一处自喂裸 slug 的 {@code Set} 当保留名
 * （{@code SkillCommandsTest}），一处走注册侧（{@code SlashRegistrySkillCommandTest}），
 * 于是 {@code reserved} 的入参口径在两个调用点上分叉了一整期都没人红：
 * {@code SkillCommands.plan} 喂裸 slug，而 {@code BotAgent.skillCommandPlan} 给的集合是
 * 带斜杠的命令键（{@code /skills}）⇒ {@code core.contains("skills")} 恒 false ⇒
 * 撞核心命令名的技能被 {@code /skills} 广告成 {@code -> /skills}、且<b>不进</b>跳过台账
 * （E2E 的 A5 是唯一抓到它的一层）。</p>
 *
 * <p>P23c 定的契约：{@code reserved} 收<b>命令全名（含前导斜杠）</b>，归一<b>只在
 * {@code SkillCommands.plan} 一处</b>做。本类因此同时钉住三件事：
 * ① 20 条核心命令<b>逐条</b>都能让同名技能被跳过并记账；② 不撞名的技能照常进表（阴性断言的猎物）；
 * ③ 账本侧与注册侧现在同源（跳过条数相等），而 {@code /skills} 本身照旧可用。</p>
 */
public class SkillCommandPlanProductionWiringTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File skillsDir;
    private String prevDir;
    private String prevEnabled;
    private BotAgent agent;

    @Before
    public void setUp() throws Exception {
        skillsDir = tmp.newFolder("skills");
        prevDir = System.getProperty("zbot.skills.dir");
        prevEnabled = System.getProperty("zbot.skills.commands");
        System.setProperty("zbot.skills.dir", skillsDir.getAbsolutePath());
        System.setProperty("zbot.skills.commands", "true");
        // 真 BotAgent，但只用到 skillCommandPlan(...)：沙箱/会话都关在 TemporaryFolder 里，不碰 ~/.zbot
        agent = BotAgent.builder(null)
                .provider(new UnusedProvider())
                .sandbox(new Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions")))
                .build();
    }

    @After
    public void tearDown() {
        if (agent != null) {
            agent.shutdown();
        }
        restore("zbot.skills.dir", prevDir);
        restore("zbot.skills.commands", prevEnabled);
    }

    private static void restore(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }

    /** 往真技能根里装一条技能；{@code slash} 非空即写 {@code slash:} frontmatter。 */
    private void install(String name, String slash) throws Exception {
        File dir = new File(skillsDir, name);
        dir.mkdirs();
        Files.write(new File(dir, "SKILL.md").toPath(),
                ("---\nname: " + name + "\ndescription: " + name + " 干什么\n"
                        + (slash == null ? "" : "slash: " + slash + "\n") + "---\n"
                        + name + " 的正文\n").getBytes(StandardCharsets.UTF_8));
    }

    /** 真生产接线：registry 走 withBuiltinCommands()，账本走 BotAgent.skillCommandPlan()。 */
    private SkillCommands.Plan planThroughProductionWiring(SlashRegistry live) {
        assertSame("接线断言：skillCommandPlan 读的就是这张 live 表", live, SlashRegistry.live());
        return agent.skillCommandPlan(SkillLoader.scan(skillsDir));
    }

    // ── 1. 撞核心名的技能：被跳过 + 进台账 + 指名"与核心命令同名" ────────────────

    @Test
    public void colliderOnCoreCommandNameIsSkippedAndAccountedThroughRealChain() throws Exception {
        install("skills", null);                       // slug == "skills" ⇒ 真核心命令 /skills
        SlashRegistry live = SlashRegistry.withBuiltinCommands();
        SkillCommands.Plan plan = planThroughProductionWiring(live);

        assertFalse("撞核心名的技能不许出现在命令表增量里: " + plan.keys(),
                plan.keys().contains("/skills"));
        assertEquals("台账必须记下这一条（D-1 的恒不触发就是这里记了 0 条）: " + plan.skipped(),
                1, plan.skipped().size());
        SkillCommands.Skipped sk = plan.skipped().get(0);
        assertEquals("skills", sk.skill);
        assertEquals("/skills", sk.key);
        assertTrue("跳过理由要指名撞了核心命令: " + sk.reason, sk.reason.contains("核心命令"));
        assertTrue("要给出显式入口: " + sk.reason, sk.reason.contains("/skill skills"));
        assertTrue("describeSkipped() 也要带上它（/skills 的账本就来自这里）",
                plan.describeSkipped().contains("核心命令"));
    }

    @Test
    public void coreCommandItselfStaysAvailableAfterGuardFires() throws Exception {
        install("skills", null);
        SlashRegistry live = SlashRegistry.withBuiltinCommands();
        SkillCommands.Plan plan = planThroughProductionWiring(live);

        assertFalse(plan.keys().contains("/skills"));
        assertNotNull("核心 /skills 不能被技能顶掉", live.find("/skills"));
        assertEquals("/skills", live.find("/skills").name());
        assertTrue("核心命令仍然不是技能命令（单源，没被悄悄改写）",
                !live.skillCommandKeys().contains("/skills"));
    }

    // ── 2. 阴性断言的猎物：不撞名的技能照常进表 ────────────────────────────────

    @Test
    public void nonCollidingSkillStillLandsInPlanThroughRealChain() throws Exception {
        install("skills", null);
        install("p23deploy", "/p23deploy");
        SlashRegistry live = SlashRegistry.withBuiltinCommands();
        SkillCommands.Plan plan = planThroughProductionWiring(live);

        assertTrue("不撞名的技能必须照常注册（否则这条守卫是把空刀）: " + plan.keys(),
                plan.keys().contains("/p23deploy"));
        assertEquals(1, plan.skipped().size());
        assertEquals("skills", plan.skipped().get(0).skill);
        assertTrue(live.skillCommandKeys().contains("/p23deploy"));
    }

    // ── 3. 20 条核心命令逐条：账本侧与注册侧同源 ────────────────────────────────

    @Test
    public void everyCoreCommandNameGuardsThePlanAndBothCallSitesAgree() throws Exception {
        List<String> cores = SlashRegistry.withBuiltinCommands().coreCommandNames();
        assertEquals("核心命令台账就是这 20 条，本用例的分母跟着它走", 20, cores.size());
        int i = 0;
        for (String core : cores) {
            install("collider" + (i++), core);         // slash: /skills … ⇒ slug == skills
        }
        SlashRegistry live = SlashRegistry.withBuiltinCommands();
        SkillCommands.Plan plan = planThroughProductionWiring(live);

        assertTrue("每一条核心命令都该挡住同名技能，多出来的是: " + plan.keys(),
                plan.entries().isEmpty());
        Set<String> ledgerKeys = new HashSet<String>();
        for (SkillCommands.Skipped s : plan.skipped()) {
            assertTrue("跳过理由必须都指核心命令: " + s, s.reason.contains("核心命令"));
            ledgerKeys.add(s.key);
        }
        assertEquals("20 条各记一笔，不许静默", 20, plan.skipped().size());
        assertEquals(new HashSet<String>(cores), ledgerKeys);
        assertEquals("账本侧与注册侧必须同源（D-1 = 一侧 20 条、一侧 0 条）",
                plan.skipped().size(), live.skillCommandSkips().size());
    }

    // ── 4. 契约：谓词收到的是命令全名，不是裸 slug ──────────────────────────────

    @Test
    public void reservedPredicateIsHandedTheSlashPrefixedKey() throws Exception {
        install("skills", null);
        install("p23deploy", "/p23deploy");
        List<String> seen = new ArrayList<String>();
        SkillCommands.plan(SkillLoader.scan(skillsDir), name -> {
            seen.add(name);
            return false;
        });
        assertEquals("喂给 reserved 的必须是命令全名（含斜杠）——归一只许一处: " + seen,
                new HashSet<String>(Arrays.asList("/p23deploy", "/skills")),
                new HashSet<String>(seen));
    }

    /** 本用例从不发起对话：只是 {@code BotAgent.Builder} 要求一个非空 provider。 */
    private static final class UnusedProvider implements LlmProvider {
        @Override
        public String name() {
            return "unused";
        }

        @Override
        public List<Model> listModels() {
            return Collections.emptyList();
        }

        @Override
        public boolean supportsModel(String modelId) {
            return false;
        }

        @Override
        public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            throw new IllegalStateException("接线层守卫不该发起 LLM 调用");
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                              Consumer<Throwable> onError) {
            throw new IllegalStateException("接线层守卫不该发起 LLM 调用");
        }
    }
}
