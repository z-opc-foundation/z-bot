package com.zifang.z.bot.slash;

import com.zifang.z.bot.skill.SkillLoader;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P23 §2：技能命令进的是<b>那一张</b>命令表（终端补全池与 /help 都从它派生），
 * 且三条硬规则在注册路径上真的生效。
 */
public class SlashRegistrySkillCommandTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File skillsDir;
    private String prevDir;
    private String prevEnabled;

    @Before
    public void setUp() throws Exception {
        skillsDir = tmp.newFolder("skills");
        prevDir = System.getProperty("zbot.skills.dir");
        prevEnabled = System.getProperty("zbot.skills.commands");
        System.setProperty("zbot.skills.dir", skillsDir.getAbsolutePath());
        System.setProperty("zbot.skills.commands", "true");
    }

    @After
    public void tearDown() {
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

    private void install(String name, String slash, String extraFm) throws Exception {
        File dir = new File(skillsDir, name);
        dir.mkdirs();
        Files.write(new File(dir, "SKILL.md").toPath(),
                ("---\nname: " + name + "\ndescription: " + name + " 干什么\n"
                        + (slash == null ? "" : "slash: " + slash + "\n")
                        + (extraFm == null ? "" : extraFm + "\n") + "---\n"
                        + name + " 的正文\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void coreCommandSetIsStillTheSingleSourceAndUndefeated() {
        SlashRegistry r = SlashRegistry.withBuiltinCommands();
        Set<String> core = new HashSet<String>(r.coreCommandNames());
        assertEquals(new HashSet<String>(Arrays.asList(
                "/new", "/clear", "/sessions", "/switch", "/tools", "/skills", "/sync", "/model",
                "/usage", "/stop", "/steer", "/queue", "/compress", "/memory", "/cron",
                "/checkpoints", "/rollback", "/background", "/agents", "/skill")), core);
        // 技能表为空时，命令表 == 核心表，没被悄悄塞进第二份清单
        assertEquals(r.coreCommandNames().size(), r.all().size());
    }

    @Test
    public void skillCommandLandsInTheSameTable() throws Exception {
        install("p23demo", "/p23demo", null);
        SlashRegistry r = SlashRegistry.withBuiltinCommands();
        SlashCommand cmd = r.find("/p23demo");
        assertNotNull("技能命令必须真的出现在命令表里", cmd);
        assertEquals("/p23demo", cmd.name());
        assertTrue(cmd.description(), cmd.description().contains("[skill p23demo]"));
        assertTrue(r.handles("p23demo") || r.handles("/p23demo"));
        List<String> described = r.describeAll();
        boolean inHelp = false;
        for (String d : described) {
            if (d.startsWith("/p23demo — ")) {
                inHelp = true;
            }
        }
        assertTrue("/help 口径也要看见它: " + described, inHelp);
        assertEquals("单源：核心 + 1 条技能命令",
                r.coreCommandNames().size() + 1, r.all().size());
        assertEquals(Arrays.asList("/p23demo"), r.skillCommandKeys());
    }

    @Test
    public void coreNameCollisionIsSkippedAndAccounted() throws Exception {
        install("skills", null, null);
        SlashRegistry r = SlashRegistry.withBuiltinCommands();
        assertEquals("撞核心名的技能不能顶掉核心命令", "/skills", r.find("/skills").name());
        assertTrue("不该多出技能命令", r.skillCommandKeys().isEmpty());
        assertEquals(1, r.skillCommandSkips().size());
        assertTrue(r.skillCommandSkips().get(0), r.skillCommandSkips().get(0).contains("核心命令"));
    }

    @Test
    public void duplicateSlugKeepsFirstInTable() throws Exception {
        install("git_helper", null, null);
        install("git-helper", null, null);
        SlashRegistry r = SlashRegistry.withBuiltinCommands();
        assertEquals(Arrays.asList("/git-helper"), r.skillCommandKeys());
        assertEquals(1, r.skillCommandSkips().size());
        assertTrue(r.skillCommandSkips().get(0).contains("保留第一个"));
    }

    @Test
    public void platformHiddenSkillStaysOutOfTable() throws Exception {
        install("winonly", "/winonly", "platforms: [definitely-not-a-real-platform]");
        SlashRegistry r = SlashRegistry.withBuiltinCommands();
        assertNull(r.find("/winonly"));
        assertTrue(r.skillCommandKeys().isEmpty());
        assertTrue(r.skillCommandSkips().get(0).contains("platforms"));
    }

    @Test
    public void refreshPicksUpNewlyInstalledSkillWithoutSecondTable() throws Exception {
        install("before", null, null);
        SlashRegistry r = SlashRegistry.withBuiltinCommands();
        assertEquals(Arrays.asList("/before"), r.skillCommandKeys());
        int coreSize = r.coreCommandNames().size();

        install("after", null, null);
        r.refreshSkillCommands();
        assertTrue(r.skillCommandKeys().containsAll(Arrays.asList("/before", "/after")));
        assertEquals("刷新只是重算技能那一段，核心命令一动不动", coreSize, r.coreCommandNames().size());
        assertEquals(coreSize + 2, r.all().size());
    }

    @Test
    public void stackedResolutionOnlyKnowsRegisteredSkillCommands() throws Exception {
        install("alpha", null, null);
        SlashRegistry r = SlashRegistry.withBuiltinCommands();
        assertEquals("/alpha", r.resolveSkillCommandKey("alpha"));
        assertEquals("/alpha", r.resolveSkillCommandKey("/alpha"));
        assertNull(r.resolveSkillCommandKey("memory"));
        assertNull(r.resolveSkillCommandKey("nope"));
    }

    @Test
    public void skillCommandsCanBeSwitchedOff() throws Exception {
        install("p23demo", "/p23demo", null);
        System.setProperty("zbot.skills.commands", "false");
        SlashRegistry r = SlashRegistry.withBuiltinCommands();
        assertTrue("关掉开关后不该有技能命令", r.skillCommandKeys().isEmpty());
        assertEquals(r.coreCommandNames().size(), r.all().size());
    }

    @Test
    public void offerableSkillsScanIsWhatTheTableIsBuiltFrom() throws Exception {
        install("visible", null, null);
        install("hidden", null, "platforms: [definitely-not-a-real-platform]");
        List<SkillLoader.Skill> all = SkillLoader.scan(skillsDir);
        List<SkillLoader.Skill> offers = SkillLoader.scanOffers(skillsDir);
        assertEquals(2, all.size());
        assertEquals(1, offers.size());
        assertEquals("visible", offers.get(0).name);
        assertFalse(offers.get(0).offerable() && all.size() == offers.size());
    }
}
