package com.zifang.z.bot.skill;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** {@link SkillLoader} frontmatter 解析与两级目录扫描。 */
public class SkillLoaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File root;

    @Before
    public void setUp() throws Exception {
        root = tmp.newFolder("skills");
        install("deployskill", "---\nname: deployskill\ndescription: 部署到 250 机器的指引\nversion: 1.2.0\nmetadata:\n  author: zifang\n  slash: /deploy\n---\n1. ssh 到 250\n2. 跑 bin/deploy.sh\n");
        // 两级目录形态（center 下发格式）
        File cat = new File(root, "ops");
        File dir = new File(cat, "rollbackskill");
        dir.mkdirs();
        write(new File(dir, "SKILL.md"), "---\nname: rollbackskill\ndescription: 回滚指引\n---\n回滚正文\n");
        // 无 SKILL.md 的目录要被跳过
        new File(root, "empty").mkdirs();
    }

    @Test
    public void scanFindsBothLevelsAndSkipsEmpty() throws Exception {
        List<SkillLoader.Skill> skills = SkillLoader.scan(root);
        assertEquals(2, skills.size());
        assertEquals("deployskill", skills.get(0).name);
        assertEquals("rollbackskill", skills.get(1).name);
    }

    @Test
    public void frontmatterFieldsAreParsed() throws Exception {
        SkillLoader.Skill s = SkillLoader.scan(root).get(0);
        assertEquals("deployskill", s.name);
        assertEquals("部署到 250 机器的指引", s.description);
        assertEquals("1.2.0", s.version);
        assertEquals("/deploy", s.slash);
        assertEquals("zifang", s.metadata.get("meta.author"));
        assertTrue(s.body, s.body.contains("bin/deploy.sh"));
        assertNotNull(s.dir);
    }

    @Test
    public void dirNameIsFallbackWhenNoFrontmatterName() throws Exception {
        File d = new File(root, "plain");
        d.mkdirs();
        write(new File(d, "SKILL.md"), "只有正文，没有 frontmatter");
        List<SkillLoader.Skill> skills = SkillLoader.scan(root);
        assertEquals(3, skills.size());
        SkillLoader.Skill plain = skills.stream()
                .filter(s -> "plain".equals(s.name)).findFirst().orElse(null);
        assertNotNull(plain);
        assertEquals("只有正文，没有 frontmatter", plain.body);
    }

    // ===== helpers =====

    private void install(String name, String content) throws Exception {
        File d = new File(root, name);
        d.mkdirs();
        write(new File(d, "SKILL.md"), content);
    }

    private static void write(File f, String content) throws Exception {
        try (FileWriter w = new FileWriter(f)) {
            w.write(content);
        }
    }
}
