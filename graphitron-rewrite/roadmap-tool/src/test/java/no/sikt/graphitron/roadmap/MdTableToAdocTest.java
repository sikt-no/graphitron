package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown tables in roadmap {@code .md} files leak into rendered AsciiDoc as
 * paragraph text with literal pipes unless the converter rewrites them to an
 * AsciiDoc {@code |===} block. These tests pin the conversion against a handful
 * of shapes that appear in real roadmap items.
 */
class MdTableToAdocTest {

    @Test
    void simpleTable_convertsToAdocBlock() {
        String md = """
            Intro paragraph.

            | A | B |
            |---|---|
            | 1 | 2 |
            | 3 | 4 |

            Trailing paragraph.
            """;
        String adoc = Main.mdBodyToAdoc(md, Main.ChangelogContext.PLAN);
        assertThat(adoc).contains("[%autowidth, options=\"header\"]\n|===\n");
        assertThat(adoc).contains("| A\n| B\n");
        assertThat(adoc).contains("| 1\n| 2\n");
        assertThat(adoc).contains("| 3\n| 4\n");
        assertThat(adoc).contains("\n|===\n");
        // The raw markdown separator must not leak through.
        assertThat(adoc).doesNotContain("|---|");
    }

    @Test
    void cellContent_appliesBoldAndLinkTransforms() {
        String md = """
            | Item | Detail |
            |---|---|
            | **bold** | see [other](other.md) |
            """;
        String adoc = Main.mdBodyToAdoc(md, Main.ChangelogContext.PLAN);
        // Markdown bold becomes AsciiDoc bold inside cells.
        assertThat(adoc).contains("| *bold*");
        // Markdown link becomes AsciiDoc xref inside cells.
        assertThat(adoc).contains("xref:other.adoc[other]");
    }

    @Test
    void emDashInCell_isSweptToSemicolon() {
        String md = """
            | A | B |
            |---|---|
            | x | y — z |
            """;
        String adoc = Main.mdBodyToAdoc(md, Main.ChangelogContext.PLAN);
        assertThat(adoc).contains("| y ; z");
        assertThat(adoc).doesNotContain("—");
    }

    @Test
    void backtickCellWithPipe_isNotSplit() {
        String md = """
            | Kind | Type |
            |---|---|
            | map | `Map<K|V>` |
            """;
        String adoc = Main.mdBodyToAdoc(md, Main.ChangelogContext.PLAN);
        // The pipe inside the code span must be preserved, escaped for AsciiDoc.
        assertThat(adoc).contains("`Map<K\\|V>`");
    }

    @Test
    void mdTableInsideCodeFence_isNotConverted() {
        String md = """
            ```
            | A | B |
            |---|---|
            | 1 | 2 |
            ```
            """;
        String adoc = Main.mdBodyToAdoc(md, Main.ChangelogContext.PLAN);
        // Code fences are passed through verbatim, separator and all.
        assertThat(adoc).contains("|---|---|");
        assertThat(adoc).doesNotContain("[%autowidth");
    }

    @Test
    void parseMdTableCells_stripsLeadingAndTrailingPipes() {
        assertThat(Main.parseMdTableCells("| a | b | c |"))
            .containsExactly("a", "b", "c");
        assertThat(Main.parseMdTableCells("a | b | c"))
            .containsExactly("a", "b", "c");
    }

    @Test
    void parseMdTableCells_unescapesEscapedPipe() {
        assertThat(Main.parseMdTableCells("| a | b \\| c | d |"))
            .containsExactly("a", "b | c", "d");
    }
}
