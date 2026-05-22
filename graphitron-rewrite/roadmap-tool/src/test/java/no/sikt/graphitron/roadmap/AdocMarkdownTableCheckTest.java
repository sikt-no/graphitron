package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the markdown-table detection contract: a {@code |---|---|} separator row
 * outside any AsciiDoc structural block is a finding, the same characters inside
 * a listing or table block are not, and the file walker skips {@code target/}.
 */
class AdocMarkdownTableCheckTest {

    @Test
    void markdownSeparator_outsideBlocks_isFlagged(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("page.adoc");
        Files.writeString(file, """
            = A page

            Truth table:

            | A | B | C |
            |---|---|---|
            | 1 | 2 | 3 |
            """);

        List<AdocMarkdownTableCheck.Finding> findings = AdocMarkdownTableCheck.scanFile(file);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).line()).isEqualTo(6);
        assertThat(findings.get(0).content()).isEqualTo("|---|---|---|");
    }

    @Test
    void asciidocTable_withDashesInCells_isNotFlagged(@TempDir Path dir) throws IOException {
        // A real AsciiDoc table whose cell happens to contain dash-only content. The
        // |=== delimiters must suppress detection inside them.
        Path file = dir.resolve("page.adoc");
        Files.writeString(file, """
            = A page

            [cols="1,1,1"]
            |===
            | A | B | C
            |---|---|---
            | 1 | 2 | 3
            |===
            """);

        assertThat(AdocMarkdownTableCheck.scanFile(file)).isEmpty();
    }

    @Test
    void listingBlock_withPipesAndDashes_isNotFlagged(@TempDir Path dir) throws IOException {
        // A code listing demonstrating markdown table syntax must not trip the check.
        Path file = dir.resolve("page.adoc");
        Files.writeString(file, """
            = A page

            For example:

            ----
            | A | B |
            |---|---|
            | 1 | 2 |
            ----

            Back to prose.
            """);

        assertThat(AdocMarkdownTableCheck.scanFile(file)).isEmpty();
    }

    @Test
    void alignedSeparator_withColons_isFlagged(@TempDir Path dir) throws IOException {
        // GFM alignment markers; the dash count is whatever the author typed.
        Path file = dir.resolve("page.adoc");
        Files.writeString(file, """
            = A page

            | A | B |
            |:---|---:|
            | 1 | 2 |
            """);

        List<AdocMarkdownTableCheck.Finding> findings = AdocMarkdownTableCheck.scanFile(file);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).content()).isEqualTo("|:---|---:|");
    }

    @Test
    void targetDirectory_isSkipped(@TempDir Path dir) throws IOException {
        // A generated .adoc under target/ may carry a markdown table that the source
        // .md plan didn't translate; that's the render-side hole tracked separately
        // and not in scope for this check.
        Path target = dir.resolve("target").resolve("generated-docs");
        Files.createDirectories(target);
        Files.writeString(target.resolve("page.adoc"), "| A | B |\n|---|---|\n| 1 | 2 |\n");

        Path source = dir.resolve("page.adoc");
        Files.writeString(source, "= A clean page\n\nNo tables here.\n");

        assertThat(AdocMarkdownTableCheck.scan(dir)).isEmpty();
    }

    @Test
    void mdFiles_areIgnored(@TempDir Path dir) throws IOException {
        // Markdown source files are out of scope; markdown table syntax is native there.
        Path md = dir.resolve("plan.md");
        Files.writeString(md, "# Plan\n\n| A | B |\n|---|---|\n| 1 | 2 |\n");

        assertThat(AdocMarkdownTableCheck.scan(dir)).isEmpty();
    }
}
