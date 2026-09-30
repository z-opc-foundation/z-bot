package com.zifang.z.bot.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** {@link PasteFolder} 单测 — 阈值、标记形状、台账展开，逐条对着 hermes 的现测契约。 */
public class PasteFolderTest {

    private static final String SIX_LINES = "alpha\nbravo\ncharlie\ndelta\necho\nfoxtrot\n";

    @Test
    public void foldsAtFiveLines() {
        assertTrue(PasteFolder.shouldFold(SIX_LINES));
        assertFalse(PasteFolder.shouldFold("a\nb\nc\nd"));
        // hermes 在粘贴入口先剥结尾换行（useComposerState.ts:144 + :191），所以这份只有 4 行、不折
        assertFalse("结尾换行不额外撑出一行去够阈值（剥尾换行后才数行数）", PasteFolder.shouldFold("a\nb\nc\nd\n"));
        assertTrue("满 5 行才折", PasteFolder.shouldFold("a\nb\nc\nd\ne\n"));
        assertFalse("纯换行的粘贴整体不当作正文", PasteFolder.shouldFold("\n\n\n\n\n"));
    }

    @Test
    public void foldsAtTwoThousandChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PasteFolder.COLLAPSE_CHARS - 1; i++) {
            sb.append('x');
        }
        assertFalse(PasteFolder.shouldFold(sb.toString()));
        sb.append('x');
        assertTrue(PasteFolder.shouldFold(sb.toString()));
    }

    @Test
    public void emptyAndNullAreNotFolded() {
        assertFalse(PasteFolder.shouldFold(""));
        assertFalse(PasteFolder.shouldFold(null));
    }

    @Test
    public void tokenCarriesLineCountAndMatchesItsOwnPattern() {
        String token = PasteFolder.token(SIX_LINES);
        assertTrue(token, token.startsWith("[[ ") && token.endsWith(" ]]"));
        assertTrue(token, token.contains("[6 lines]"));
        java.util.regex.Matcher m = PasteFolder.TOKEN.matcher(token);
        assertTrue(m.find());
        assertEquals("标记里不许出现第二个 ]] 提前终结匹配", token, m.group());
    }

    @Test
    public void blankPasteDegradesToCountOnlyToken() {
        assertEquals("[[ [3 lines] ]]", PasteFolder.token("\n\n\n", 3));
    }

    @Test
    public void previewCollapsesWhitespaceAndEscapesClosingBrackets() {
        String preview = PasteFolder.edgePreview("a\n\n  b ]] c");
        assertEquals("a b ] ] c", preview);
    }

    @Test
    public void previewKeepsHeadAndTailAcrossLongText() {
        StringBuilder sb = new StringBuilder("0123456789abcdefghij");
        for (int i = 0; i < 200; i++) {
            sb.append("x");
        }
        sb.append("TAILTAILTAILTAIL");
        String preview = PasteFolder.edgePreview(sb.toString());
        assertTrue(preview, preview.startsWith("0123456789abcdef.. "));
        assertTrue(preview, preview.endsWith("TAILTAILTAILTAIL"));
    }

    @Test
    public void lineCountUsesCompactUnits() {
        assertEquals("5", PasteFolder.compact(5));
        assertEquals("999", PasteFolder.compact(999));
        assertEquals("1k", PasteFolder.compact(1000));
        assertEquals("1.2k", PasteFolder.compact(1234));
        assertEquals("2k", PasteFolder.compact(2000));
        assertEquals("12.3k", PasteFolder.compact(12345));
    }

    @Test
    public void sameLabelSnipsExpandFirstInFirstOut() {
        PasteFolder.Snips snips = new PasteFolder.Snips();
        String label = PasteFolder.token("a\nb\nc\nd\ne\n", 5);
        snips.record(label, "first\nsecond\n");
        snips.record(label, "other\none\n");
        String buffer = label + " " + label;
        assertEquals("first\nsecond\n other\none\n", snips.expand(buffer));
    }

    @Test
    public void unknownTokensAreLeftAlone() {
        PasteFolder.Snips snips = new PasteFolder.Snips();
        snips.record("[[ [5 lines] ]]", "known\n");
        assertEquals("known\n [[ [9 lines] ]]",
                snips.expand("[[ [5 lines] ]] [[ [9 lines] ]]"));
    }

    @Test
    public void expansionIsRepeatableUntilSubmitClears() {
        PasteFolder.Snips snips = new PasteFolder.Snips();
        String label = PasteFolder.token(SIX_LINES);
        snips.record(label, SIX_LINES);
        assertEquals(SIX_LINES, snips.expand(label));
        assertEquals("台账要到提交才清，展开本身不改状态", SIX_LINES, snips.expand(label));
        snips.clear();
        assertEquals(label, snips.expand(label));
        assertEquals(0, snips.size());
    }

    @Test
    public void ledgerDropsOldestBeyondCap() {
        PasteFolder.Snips snips = new PasteFolder.Snips();
        String[] labels = new String[PasteFolder.SNIP_MAX_COUNT + 1];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "[[ [5 lines] item" + i + " ]]";
            snips.record(labels[i], labels[i] + "\nb\nc\nd\ne\n");
        }
        assertEquals(PasteFolder.SNIP_MAX_COUNT, snips.size());
        assertEquals("最旧那条已被挤掉，原样留着", labels[0], snips.expand(labels[0]));
        assertNotEquals(labels[labels.length - 1], snips.expand(labels[labels.length - 1]));
    }

    @Test
    public void expandPassesThroughPlainText() {
        PasteFolder.Snips snips = new PasteFolder.Snips();
        snips.record("[[ [5 lines] ]]", "a\nb\nc\nd\ne\n");
        assertEquals("no tokens here", snips.expand("no tokens here"));
        assertEquals(null, snips.expand(null));
    }
}
