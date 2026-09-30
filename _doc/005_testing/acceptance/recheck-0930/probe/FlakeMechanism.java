import com.zifang.z.bot.memory.MemorySection;
import com.zifang.z.bot.memory.MemoryStore;
import java.io.File;
import java.nio.file.Files;

/**
 * 只读取证：证明 "旧正文要被换掉" 那条间歇红的机制 ——
 * 条目行的形状是 "- [Instant.now()] 正文"，而断言是在**整页文本**上找 "250"，
 * 时间戳的微秒位是墙钟，所以 .439250 / .442250 这类取值会让换掉的正文之外再留一个 "250"。
 * 顺带证第二件事：MemoryWriteGate.locate 是在**整行**（含时间戳）上做 contains，
 * 所以纯数字的 old_text 可能命中的是时间戳而不是正文。
 */
public class FlakeMechanism {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("zbot-mem-probe").toFile();

        // —— 机制 A：正文换干净了，页面里还留着一个来自时间戳的 "250"
        File f = new File(dir, "MEMORY.md");
        MemoryStore s = new MemoryStore(dir);
        // 用真 API 走一遍测试的同一段：两条 append + 一次 replace
        s.appendEntry(MemorySection.MEMORY, "部署走 250 机器");
        s.appendEntry(MemorySection.MEMORY, "偏好简短回复");
        s.replaceEntry(MemorySection.MEMORY, "250", "部署走 251 机器");
        String page = s.readMemory();
        System.out.println("A_real_api|bodies_have_251=" + s.entryBodies(MemorySection.MEMORY).contains("部署走 251 机器")
                + "|page_contains_250=" + page.contains("250"));
        System.out.println("A_real_api|page=" + page.replace("\n", "\\n"));

        // —— 机制 B：人造一条时间戳里带 250 的条目（不靠运气），断言必红
        File dir2 = Files.createTempDirectory("zbot-mem-probe2").toFile();
        MemoryStore s2 = new MemoryStore(dir2);
        String crafted = "- [2026-09-30T12:27:28.439250Z] 偏好简短回复\n"
                + "- [2026-09-30T12:27:29.000123Z] 部署走 251 机器";
        s2.rewritePage(MemorySection.MEMORY, crafted);
        String p2 = s2.readMemory();
        System.out.println("B_crafted|body_has_250=" + containsBody(s2, "250")
                + "|page_contains_250=" + p2.contains("250"));

        // —— 机制 C：纯数字 old_text 命中的是时间戳，不是正文 ⇒ 删错条目且不报错
        File dir3 = Files.createTempDirectory("zbot-mem-probe3").toFile();
        MemoryStore s3 = new MemoryStore(dir3);
        s3.rewritePage(MemorySection.MEMORY,
                "- [2026-09-30T12:27:28.439250Z] 偏好简短回复");
        try {
            s3.removeEntry(MemorySection.MEMORY, "250");
            System.out.println("C_misdelete|removed_without_error=true|entries_now="
                    + s3.entries(MemorySection.MEMORY).size()
                    + "|body_still_had_250=" + containsBody(s3, "250"));
        } catch (Exception e) {
            System.out.println("C_misdelete|threw=" + e.getClass().getSimpleName() + "|" + e.getMessage());
        }

        // —— 阳性对照：正文里真有 250 时，同一条 remove 应当命中并删掉
        File dir4 = Files.createTempDirectory("zbot-mem-probe4").toFile();
        MemoryStore s4 = new MemoryStore(dir4);
        s4.appendEntry(MemorySection.MEMORY, "部署走 250 机器");
        s4.removeEntry(MemorySection.MEMORY, "250");
        System.out.println("D_control|entries_after=" + s4.entries(MemorySection.MEMORY).size());
    }

    private static boolean containsBody(MemoryStore s, String needle) {
        for (String b : s.entryBodies(MemorySection.MEMORY)) {
            if (b.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
