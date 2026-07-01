package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void literalBlock_withPipesAndDashes_isNotFlagged(@TempDir Path dir) throws IOException {
        // A `....` literal block reproduces output verbatim and may contain whatever
        // characters the author pasted in; markdown-table separators among them must
        // not trip the check.
        Path file = dir.resolve("page.adoc");
        Files.writeString(file, """
            = A page

            Sample output:

            ....
            | A | B |
            |---|---|
            | 1 | 2 |
            ....

            Back to prose.
            """);

        assertThat(AdocMarkdownTableCheck.scanFile(file)).isEmpty();
    }

    @Test
    void commentBlock_withPipesAndDashes_isNotFlagged(@TempDir Path dir) throws IOException {
        // A `////` comment block is stripped from rendered output entirely; whatever
        // shape it carries cannot leak into the page and must not be flagged.
        Path file = dir.resolve("page.adoc");
        Files.writeString(file, """
            = A page

            ////
            | A | B |
            |---|---|
            | 1 | 2 |
            ////

            Prose continues.
            """);

        assertThat(AdocMarkdownTableCheck.scanFile(file)).isEmpty();
    }

    @Test
    void passthroughBlock_withPipesAndDashes_isNotFlagged(@TempDir Path dir) throws IOException {
        // A `++++` passthrough block emits its content verbatim into the output
        // (typically raw HTML); a markdown-table-shaped fragment in there is the
        // author's intent, not a bug.
        Path file = dir.resolve("page.adoc");
        Files.writeString(file, """
            = A page

            ++++
            | A | B |
            |---|---|
            | 1 | 2 |
            ++++

            Prose continues.
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

    @Test
    void run_withFindings_throwsBuildFailure(@TempDir Path dir) throws IOException {
        // The verify-phase entry point must throw rather than return non-zero (which the Main
        // dispatcher turns into System.exit). exec-maven-plugin runs `java` in the Maven JVM,
        // so a System.exit would kill Maven before it prints BUILD FAILURE; a BuildFailure lets
        // the plugin surface the normal [ERROR] / BUILD FAILURE summary.
        Files.writeString(dir.resolve("page.adoc"), """
            = A page

            | A | B |
            |---|---|
            | 1 | 2 |
            """);

        assertThatThrownBy(() -> AdocMarkdownTableCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_clean_returnsZero(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("page.adoc"), "= A clean page\n\nNo tables here.\n");

        assertThat(AdocMarkdownTableCheck.run(List.of(dir.toString()))).isZero();
    }

    @Test
    void run_usageError_returnsExitCodeWithoutThrowing() throws IOException {
        // Usage / argument errors are CLI dev errors, not a verify-phase tripwire, so they keep
        // returning the conventional 64 (EX_USAGE) for the dispatcher to System.exit on.
        assertThat(AdocMarkdownTableCheck.run(List.of())).isEqualTo(64);
    }
}
