package com.zifang.z.bot.skill;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P23 §2：技能 → 斜杠命令的四条硬规则（slug 化、撞核心名跳过、同名保第一个、最多叠 5 个）。
 */
public class SkillCommandsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 她的核心命令集里有的东西，测试里手动当保留名。 */
    private static final Set<String> RESERVED =
            new HashSet<String>(Arrays.asList("help", "skills", "sync", "new", "stop", "model"));

    /** 任何真实平台都不会等于它 —— 用来稳定地造出"被平台门藏掉"的技能。 */
    private static final String NO_SUCH_PLATFORM = "definitely-not-a-real-platform";

    private SkillLoader.Skill skill(String name, String slash) throws Exception {
        File dir = new File(tmp.getRoot(), name);
        dir.mkdirs();
        Files.write(new File(dir, "SKILL.md").toPath(),
                ("---\nname: " + name + "\ndescription: " + name + " 的说明\n"
                        + (slash == null ? "" : "slash: " + slash + "\n") + "---\n"
                        + name + " 正文\n").getBytes(StandardCharsets.UTF_8));
        SkillLoader.Skill s = SkillLoader.tryParse(dir);
        assertNotNull(s);
        assertTrue("夹具本身必须是可供货技能: " + s.hiddenReason, s.offerable());
        return s;
    }

    // ── slug 规则 ────────────────────────────────────────────────────────────

    @Test
    public void slugFollowsHermesNormalization() {
        assertEquals("git-helper", SkillCommands.slug("git_helper"));
        assertEquals("git-helper", SkillCommands.slug("Git Helper"));
        assertEquals("deploy", SkillCommands.slug("/deploy"));
        assertEquals("c-tool", SkillCommands.slug("C++ tool"));
        assertEquals("a-b", SkillCommands.slug("a___b"));
        assertEquals("", SkillCommands.slug("中文技能"));
    }

    @Test
    public void explicitSlashWinsOverGeneratedSlug() throws Exception {
        SkillCommands.Plan plan = SkillCommands.plan(
                Collections.singletonList(skill("deployskill", "/deploy")), RESERVED::contains);
        assertEquals(1, plan.entries().size());
        assertEquals("/deploy", plan.entries().get(0).key);
    }

    @Test
    public void emptySlugIsSkippedAndAccounted() throws Exception {
        SkillLoader.Skill s = skill("中文技能", null);
        SkillCommands.Plan plan = SkillCommands.plan(Collections.singletonList(s), RESERVED::contains);
        assertTrue(plan.entries().isEmpty());
        assertEquals(1, plan.skipped().size());
        assertTrue("要记账为什么没进: " + plan.skipped().get(0).reason,
                plan.skipped().get(0).reason.contains("slug"));
    }

    // ── 规则一：撞核心命令名 ⇒ 跳过并记账（她 :386） ───────────────────────────

    @Test
    public void collidingWithCoreCommandSkipsAndAccounts() throws Exception {
        SkillLoader.Skill s = skill("skills", null);       // slug 算出来就是 /skills —— 核心命令
        SkillCommands.Plan plan = SkillCommands.plan(Collections.singletonList(s), RESERVED::contains);
        assertTrue("撞核心名的技能不许进命令表", plan.entries().isEmpty());
        assertEquals(1, plan.skipped().size());
        String reason = plan.skipped().get(0).reason;
        assertTrue("跳过理由要写明撞了核心命令: " + reason, reason.contains("核心命令"));
        assertTrue("要给出替代入口 /skill: " + reason, reason.contains("/skill skills"));
    }

    @Test
    public void nonCollidingCommandIsRegistered() throws Exception {
        SkillCommands.Plan plan = SkillCommands.plan(
                Collections.singletonList(skill("rollbackhelper", null)), RESERVED::contains);
        assertEquals(Collections.singletonList("/rollbackhelper"), plan.keys());
    }

    // ── 规则二：同一个 slug 撞车 ⇒ 保第一个（她 :399） ──────────────────────────

    @Test
    public void duplicateSlugKeepsFirstAndSkipsRest() throws Exception {
        List<SkillLoader.Skill> skills = Arrays.asList(
                skill("git_helper", null), skill("git-helper", null), skill("Git Helper", null));
        SkillCommands.Plan plan = SkillCommands.plan(skills, RESERVED::contains);
        assertEquals("三个名字归一化到同一个 slug，只该留一条", 1, plan.entries().size());
        assertEquals("保的是第一个", "git_helper", plan.entries().get(0).skill.name);
        assertEquals(2, plan.skipped().size());
        for (SkillCommands.Skipped sk : plan.skipped()) {
            assertTrue("要写明保留第一个: " + sk.reason, sk.reason.contains("保留第一个"));
            assertTrue("要点名保的是谁: " + sk.reason, sk.reason.contains("git_helper"));
        }
    }

    @Test
    public void platformHiddenSkillIsAccountedNotSilentlyDropped() throws Exception {
        File dir = new File(tmp.getRoot(), "ghostskill");
        dir.mkdirs();
        Files.write(new File(dir, "SKILL.md").toPath(),
                ("---\nname: ghostskill\nplatforms: [" + NO_SUCH_PLATFORM + "]\n---\n正文\n")
                        .getBytes(StandardCharsets.UTF_8));
        SkillLoader.Skill s = SkillLoader.tryParse(dir);
        assertTrue("夹具应当被平台门藏掉: " + (s == null ? "解析失败" : s.hiddenReason),
                s != null && !s.offerable());
        SkillCommands.Plan plan = SkillCommands.plan(Collections.singletonList(s), RESERVED::contains);
        assertTrue("被藏掉的技能不进命令表", plan.entries().isEmpty());
        assertEquals(1, plan.skipped().size());
        assertTrue("要写明为什么: " + plan.skipped().get(0).reason,
                plan.skipped().get(0).reason.contains("不进命令表")
                        && plan.skipped().get(0).reason.contains(NO_SUCH_PLATFORM));
    }

    // ── 规则三：最多叠 5 个（她 _MAX_STACKED_SKILLS = 5） ────────────────────────

    @Test
    public void stackedLimitIsFive() {
        assertEquals(5, SkillCommands.MAX_STACKED_SKILLS);
    }

    private static final Set<String> TABLE =
            new HashSet<String>(Arrays.asList("/a", "/b", "/c", "/d", "/e", "/f"));

    private static String resolveKnown(String token) {
        String k = "/" + token;
        return TABLE.contains(k) ? k : null;
    }

    @Test
    public void stackedInvocationConsumesAllLeadingSkillTokensUpToFive() {
        SkillCommands.Stack five = SkillCommands.splitStacked("/b /c /d /e /f 干活",
                SkillCommandsTest::resolveKnown);
        assertEquals("含第一个命令在内最多 5 个 ⇒ 这里最多追加 4 个",
                Arrays.asList("/b", "/c", "/d", "/e"), five.keys);
        // 到上限就停：第 6 个令牌留在指令里（hermes 的 while len(keys) < MAX-1 就是这个行为）
        assertEquals("/f 干活", five.instruction);
    }

    @Test
    public void stackingStopsAtFirstNonSkillToken() {
        SkillCommands.Stack stop = SkillCommands.splitStacked("/b 先这样 /c 再说",
                SkillCommandsTest::resolveKnown);
        assertEquals(Collections.singletonList("/b"), stop.keys);
        assertEquals("先这样 /c 再说", stop.instruction);

        SkillCommands.Stack none = SkillCommands.splitStacked("直接说话 /b",
                SkillCommandsTest::resolveKnown);
        assertTrue(none.keys.isEmpty());
        assertEquals("直接说话 /b", none.instruction);

        SkillCommands.Stack plain = SkillCommands.splitStacked("办事", SkillCommandsTest::resolveKnown);
        assertTrue(plain.keys.isEmpty());
        assertEquals("办事", plain.instruction);
    }

    @Test
    public void repeatedStackedKeyStopsParsingLikeHermes() {
        // hermes: `if cmd_key is None or cmd_key in keys: break` —— 撞到重复的令牌就停，
        // 后面的令牌留在用户指令里，不 skip-and-continue。
        SkillCommands.Stack s = SkillCommands.splitStacked("/b /b /a go", SkillCommandsTest::resolveKnown);
        assertEquals(Arrays.asList("/b"), s.keys);
        assertEquals("/b /a go", s.instruction);
    }

    // ── 注入消息形态 ───────────────────────────────────────────────────────────

    @Test
    public void invocationMessageCarriesSkillBodyDirectoryAndInstruction() throws Exception {
        SkillLoader.Skill a = skill("alpha", null);
        SkillLoader.Skill b = skill("beta", null);
        String msg = SkillCommands.buildInvocationMessage(Arrays.asList(a, b), "把两件事一起办");
        assertTrue(msg.contains("alpha"));
        assertTrue(msg.contains("beta"));
        assertTrue(msg.contains("2 个技能"));
        assertTrue(msg.contains("alpha 正文"));
        assertTrue(msg.contains("[Skill directory:"));
        assertTrue(msg.contains("把两件事一起办"));
        assertTrue("要写明正文只是语料: " + msg, msg.contains("语料"));

        String single = SkillCommands.buildInvocationMessage(Collections.singletonList(a), "");
        assertTrue(single.contains("Skill 'alpha'"));
    }

    @Test
    public void planResolveAcceptsBareNameAndKey() throws Exception {
        SkillCommands.Plan plan = SkillCommands.plan(
                Collections.singletonList(skill("deployskill", "/deploy")), RESERVED::contains);
        assertNotNull(plan.resolve("/deploy"));
        assertNotNull(plan.resolve("deploy"));
        assertNotNull(plan.resolve("deployskill"));
        assertNull(plan.resolve("nope"));
        List<String> keys = new ArrayList<String>(plan.keys());
        assertEquals(Collections.singletonList("/deploy"), keys);
    }
}
