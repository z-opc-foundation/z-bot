package com.zifang.z.bot.skill;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P23 §1：SKILL.md frontmatter 补齐 {@code platforms} / {@code environments} /
 * {@code prerequisites} / {@code metadata.hermes.*}，并且每个字段都必须落在行为上。
 */
public class SkillFrontmatterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SkillLoader.Skill write(String name, String frontmatter, String body) throws Exception {
        File dir = new File(tmp.getRoot(), name);
        dir.mkdirs();
        Files.write(new File(dir, "SKILL.md").toPath(),
                ("---\n" + frontmatter + "---\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        SkillLoader.Skill s = SkillLoader.tryParse(dir);
        assertNotNull(s);
        return s;
    }

    // ── platforms ────────────────────────────────────────────────────────────

    @Test
    public void platformsParsedFromFlowAndBlockLists() throws Exception {
        SkillLoader.Skill flow = write("flowy", "name: flowy\nplatforms: [linux, macos, windows]\n",
                "正文");
        assertEquals(Arrays.asList("linux", "macos", "windows"), flow.platforms);

        SkillLoader.Skill block = write("blocky",
                "name: blocky\nplatforms:\n  - macos\n  - linux\n", "正文");
        assertEquals(Arrays.asList("macos", "linux"), block.platforms);
    }

    @Test
    public void platformMatcherIsOrSemanticsAndAbsentMeansAll() {
        assertTrue(SkillLoader.matchesPlatform(Collections.<String>emptyList(), "macos"));
        assertTrue(SkillLoader.matchesPlatform(Arrays.asList("macos"), "macos"));
        assertTrue(SkillLoader.matchesPlatform(Arrays.asList("linux", "macos"), "macos"));
        assertFalse(SkillLoader.matchesPlatform(Arrays.asList("windows"), "macos"));
        // hermes 的别名映射：darwin→macos、android→linux
        assertTrue(SkillLoader.matchesPlatform(Arrays.asList("darwin"), "macos"));
        assertTrue(SkillLoader.matchesPlatform(Arrays.asList("android"), "linux"));
    }

    @Test
    public void platformMismatchHidesSkillWithReadableReason() throws Exception {
        SkillLoader.Skill s = write("winonly", "name: winonly\nplatforms: [windows]\n", "正文");
        String current = SkillLoader.currentPlatform();
        if ("windows".equals(current)) {
            assertTrue(s.offerable());
            return;
        }
        assertFalse("platforms 不匹配 ⇒ 不该被供货", s.offerable());
        assertNotNull(s.hiddenReason);
        assertTrue("理由要点名 platforms 与当前平台，实测: " + s.hiddenReason,
                s.hiddenReason.contains("platforms=[windows]") && s.hiddenReason.contains(current));
        List<SkillLoader.Skill> offered = SkillLoader.scanOffers(tmp.getRoot());
        for (SkillLoader.Skill x : offered) {
            assertFalse("隐藏技能不得进供货列表", "winonly".equals(x.name));
        }
    }

    // ── environments ─────────────────────────────────────────────────────────

    @Test
    public void environmentGateIsOfferTimeOnlyAndFailsOpenOnUnknownTags() {
        List<String> none = Collections.emptyList();
        // OR 语义：声明了 kanban/docker 但当前哪个都不在 ⇒ 隐藏
        assertFalse(SkillLoader.matchesEnvironment(Arrays.asList("kanban", "docker"), none));
        assertFalse(SkillLoader.matchesEnvironment(Arrays.asList("docker"), none));
        assertTrue(SkillLoader.matchesEnvironment(Arrays.asList("docker"), Arrays.asList("docker")));
        assertTrue(SkillLoader.matchesEnvironment(Arrays.asList("kanban", "docker"),
                Arrays.asList("kanban")));
        // 看不懂的标签不藏技能（hermes: fail open）
        assertTrue(SkillLoader.matchesEnvironment(Arrays.asList("spaceship"), none));
        assertTrue(SkillLoader.matchesEnvironment(Collections.<String>emptyList(), none));
    }

    @Test
    public void environmentMismatchIsRecordedAsHiddenReason() throws Exception {
        String prev = System.getProperty("zbot.skills.environments");
        System.setProperty("zbot.skills.environments", "");
        try {
            SkillLoader.Skill s = write("s6only", "name: s6only\nenvironments: [s6]\n", "正文");
            assertFalse(s.offerable());
            assertTrue("理由要点名 environments，实测: " + s.hiddenReason,
                    s.hiddenReason.contains("environments=[s6]"));
        } finally {
            if (prev == null) {
                System.clearProperty("zbot.skills.environments");
            } else {
                System.setProperty("zbot.skills.environments", prev);
            }
        }
    }

    // ── prerequisites ────────────────────────────────────────────────────────

    @Test
    public void missingPrerequisitesDegradeInsteadOfDroppingTheSkill() throws Exception {
        SkillLoader.Skill s = write("needskey",
                "name: needskey\ndescription: 需要密钥\nprerequisites:\n  env_vars: [ZBOT_DEFINITELY_UNSET_XYZ]\n"
                        + "  commands: [zbot-no-such-command-xyz]\n", "正文");
        assertEquals(Collections.singletonList("ZBOT_DEFINITELY_UNSET_XYZ"), s.requiredEnvVars);
        assertEquals(Collections.singletonList("zbot-no-such-command-xyz"), s.requiredCommands);
        assertTrue("缺前置必须照样供货（hermes: 继续加载并说明降级）", s.offerable());
        assertNull(s.hiddenReason);
        assertNotNull("缺前置要给降级说明", s.setupNote);
        assertTrue(s.setupNote.contains("ZBOT_DEFINITELY_UNSET_XYZ"));
        assertTrue(SkillLoader.isDegraded(s));
    }

    @Test
    public void satisfiedPrerequisitesProduceNoSetupNote() throws Exception {
        String key = "ZBOT_P23_TEST_PRESENT";
        // 没法在测试里给子进程加环境变量，因此这里只验命令侧：PATH 上一定有的 sh
        SkillLoader.Skill s = write("okprereq",
                "name: okprereq\nprerequisites:\n  commands: [sh]\n", "正文");
        assertTrue(s.missingCommands.isEmpty());
        assertNull("前置齐了就不该有降级说明（" + key + "）", s.setupNote);
    }

    // ── metadata.hermes.* ────────────────────────────────────────────────────

    @Test
    public void hermesMetadataNamespaceIsFlattenedWithDotKeys() throws Exception {
        SkillLoader.Skill s = write("tagged",
                "name: tagged\ndescription: \"带引号的描述\"\nversion: 1.0.0\n"
                        + "metadata:\n  hermes:\n    tags: [qa, testing]\n"
                        + "    related_skills: [deployskill]\n", "正文");
        assertEquals("带引号的描述", s.description);
        assertEquals(Arrays.asList("qa", "testing"), s.tags);
        assertEquals(Collections.singletonList("deployskill"), s.relatedSkills);
        assertEquals("qa,testing", s.metadata.get("hermes.tags"));
        assertEquals("deployskill", s.metadata.get("hermes.related_skills"));
    }

    @Test
    public void slashIsReadFromTopLevelAndFromHermesNamespace() throws Exception {
        assertEquals("/deploy", write("topslash", "name: topslash\nslash: /deploy\n", "正文").slash);
        assertEquals("/hermesway",
                write("hermesslash",
                        "name: hermesslash\nmetadata:\n  hermes:\n    slash: /hermesway\n", "正文").slash);
        // 历史写法（metadata 下直接一层 slash）仍然认
        assertEquals("/legacy",
                write("legacyslash",
                        "name: legacyslash\nmetadata:\n  author: zifang\n  slash: /legacy\n", "正文").slash);
    }

    @Test
    public void legacyMetaKeyConventionIsPreserved() throws Exception {
        SkillLoader.Skill s = write("legacymeta",
                "name: legacymeta\nmetadata:\n  author: zifang\n", "正文");
        assertEquals("zifang", s.metadata.get("meta.author"));
    }

    @Test
    public void unknownFrontmatterKeysDoNotBreakParsing() throws Exception {
        SkillLoader.Skill s = write("odd",
                "name: odd\nlicense: MIT\ncompatibility: Requires X\nallowed-tools: [exec]\n", "正文");
        assertEquals("odd", s.name);
        assertTrue("非 metadata 块的键不该混进 metadata: " + s.metadata, s.metadata.isEmpty());
        assertTrue(s.offerable());
    }
}
