package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The concepts-page half of the two-emitter link model (R486): repo-relative
 * hrefs authored in {@code roadmap/concepts/<slug>.html} are rewritten to the
 * rendered-site layout at staging time, through the same {@link LinkTarget}
 * classification the AsciiDoc emitter reads ({@link LinkTargetRoundTripTest}
 * pins that direction). Also pins the {@code data-concept-title} contract and
 * the derived listings.
 */
class ConceptPagesTest {

    @TempDir
    Path roadmapDir;

    private Path liveItem() throws IOException {
        Path item = roadmapDir.resolve("live-item.md");
        Files.writeString(item, "---\nid: R1\n---\n\n# Live\n");
        return item;
    }

    @Test
    void liveItemTarget_mapsToRenderedPlanPage() throws IOException {
        liveItem();
        assertThat(ConceptPages.mapHref("../live-item.md", roadmapDir))
            .isEqualTo("../plans/live-item.html");
        assertThat(ConceptPages.mapHref("../live-item.md#section", roadmapDir))
            .isEqualTo("../plans/live-item.html#section");
    }

    @Test
    void shippedItemTarget_fallsBackToChangelog() {
        // No item file: deleted on Done; the changelog is the durable record.
        assertThat(ConceptPages.mapHref("../shipped-item.md", roadmapDir))
            .isEqualTo("../changelog.html");
    }

    @Test
    void readmeAndChangelog_mapToSiteEquivalents() {
        assertThat(ConceptPages.mapHref("../README.md", roadmapDir))
            .isEqualTo("../index.html");
        assertThat(ConceptPages.mapHref("../changelog.md", roadmapDir))
            .isEqualTo("../changelog.html");
    }

    @Test
    void manualAndArchitectureDeepPaths_mapToRenderedHtml() {
        assertThat(ConceptPages.mapHref("../../docs/manual/reference/directives/reference.adoc", roadmapDir))
            .isEqualTo("../../manual/reference/directives/reference.html");
        assertThat(ConceptPages.mapHref("../../docs/architecture/explanation/dispatch-axes.adoc", roadmapDir))
            .isEqualTo("../../architecture/explanation/dispatch-axes.html");
        assertThat(ConceptPages.mapHref("../../docs/manual/how-to/polymorphic-types.adoc#anchor", roadmapDir))
            .isEqualTo("../../manual/how-to/polymorphic-types.html#anchor");
    }

    @Test
    void externalUrl_isUntouched() {
        assertThat(ConceptPages.mapHref("https://example.com/x", roadmapDir))
            .isEqualTo("https://example.com/x");
    }

    @Test
    void unknownRelativeTarget_isUntouched() {
        assertThat(ConceptPages.mapHref("../notes/thing.txt", roadmapDir))
            .isEqualTo("../notes/thing.txt");
    }

    @Test
    void assetAndSiblingConceptReferences_areUntouched() {
        assertThat(ConceptPages.mapHref("concepts.css", roadmapDir))
            .isEqualTo("concepts.css");
        assertThat(ConceptPages.mapHref("other-concept.html", roadmapDir))
            .isEqualTo("other-concept.html");
        assertThat(ConceptPages.mapHref("#same-page", roadmapDir))
            .isEqualTo("#same-page");
    }

    @Test
    void rewriteHrefs_rewritesAttributeValuesOnly() throws IOException {
        liveItem();
        String html = """
            <link rel="stylesheet" href="concepts.css">
            <a href="../live-item.md">plan</a>
            <a href="../shipped-item.md">shipped</a>
            <p>prose mentioning href="../live-item.md" stays... actually inside quotes it is rewritten;
               content outside href attributes like ../live-item.md is untouched.</p>
            """;
        String out = ConceptPages.rewriteHrefs(html, roadmapDir);
        assertThat(out).contains("href=\"concepts.css\"");
        assertThat(out).contains("href=\"../plans/live-item.html\"");
        assertThat(out).contains("href=\"../changelog.html\"");
        assertThat(out).contains("content outside href attributes like ../live-item.md is untouched");
    }

    @Test
    void extractTitle_readsAttributeValue() {
        String html = "<h1 data-concept-title=\"Join paths, explained\">Join <code>paths</code></h1>";
        assertThat(ConceptPages.extractTitle("page.html", html))
            .isEqualTo("Join paths, explained");
    }

    @Test
    void missingTitleAttribute_failsTheBuildNamingTheFile() {
        assertThatThrownBy(() -> ConceptPages.extractTitle("broken.html", "<h1>No attribute</h1>"))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("broken.html");
    }

    @Test
    void blankTitleAttribute_failsTheBuild() {
        assertThatThrownBy(() -> ConceptPages.extractTitle("blank.html",
            "<h1 data-concept-title=\"  \">Blank</h1>"))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("blank.html");
    }

    @Test
    void readTitles_returnsSlugToTitleSortedBySlug() throws IOException {
        Path concepts = Files.createDirectories(roadmapDir.resolve("concepts"));
        Files.writeString(concepts.resolve("zeta.html"), "<h1 data-concept-title=\"Zeta\">z</h1>");
        Files.writeString(concepts.resolve("alpha.html"), "<h1 data-concept-title=\"Alpha\">a</h1>");
        Files.writeString(concepts.resolve("concepts.css"), "body{}");

        Map<String, String> titles = ConceptPages.readTitles(concepts);

        assertThat(titles.keySet()).containsExactly("alpha", "zeta");
        assertThat(titles).containsEntry("alpha", "Alpha").containsEntry("zeta", "Zeta");
    }

    @Test
    void readTitles_absentDirectory_isEmpty() throws IOException {
        assertThat(ConceptPages.readTitles(roadmapDir.resolve("concepts"))).isEmpty();
    }

    @Test
    void stage_rewritesPagesAndCopiesAssetsByteForByte() throws IOException {
        liveItem();
        Path concepts = Files.createDirectories(roadmapDir.resolve("concepts"));
        Files.writeString(concepts.resolve("page.html"),
            "<h1 data-concept-title=\"Page\">p</h1><a href=\"../live-item.md\">x</a>");
        String css = ":root{--ink:#1a2233}\n";
        Files.writeString(concepts.resolve("concepts.css"), css);
        Files.writeString(concepts.resolve("concepts.js"), "function choose(){}\n");
        Path out = roadmapDir.resolve("out");

        ConceptPages.stage(roadmapDir, out);

        assertThat(Files.readString(out.resolve("page.html")))
            .contains("href=\"../plans/live-item.html\"");
        assertThat(Files.readString(out.resolve("concepts.css"))).isEqualTo(css);
        assertThat(out.resolve("concepts.js")).exists();
    }

    @Test
    void readmeListing_rendersDerivedSectionSortedBySlug() {
        Main.Item item = new Main.Item("some-item", "R1", "Some item",
            "In Progress", null, null, false, null, null, List.of(), null, null, "");
        String withConcepts = Main.render(List.of(item),
            Map.of("zeta", "Zeta title", "alpha", "Alpha title"));

        assertThat(withConcepts).contains("## Concept explainers");
        assertThat(withConcepts).contains("- [Alpha title](concepts/alpha.html)\n- [Zeta title](concepts/zeta.html)");
        // Adding a page without regenerating the README is drift: the rendered
        // string changes, so verify's byte comparison fails the build.
        assertThat(withConcepts).isNotEqualTo(Main.render(List.of(item), Map.of()));
    }

    @Test
    void readmeListing_omittedWhenNoPages() {
        Main.Item item = new Main.Item("some-item", "R1", "Some item",
            "In Progress", null, null, false, null, null, List.of(), null, null, "");
        assertThat(Main.render(List.of(item), Map.of()))
            .isEqualTo(Main.render(List.of(item)))
            .doesNotContain("Concept explainers");
    }

    @Test
    void statusBoardListing_emitsLinkMacros() {
        Main.Item item = new Main.Item("some-item", "R1", "Some item",
            "In Progress", null, null, false, null, null, List.of(), null, null, "");
        String board = Main.renderAdocStatusBoard(List.of(item), Map.of("alpha", "Alpha title"));

        assertThat(board).contains("== Concept explainers");
        assertThat(board).contains("* link:concepts/alpha.html[Alpha title]");
        assertThat(Main.renderAdocStatusBoard(List.of(item), Map.of()))
            .isEqualTo(Main.renderAdocStatusBoard(List.of(item)))
            .doesNotContain("Concept explainers");
    }
}
