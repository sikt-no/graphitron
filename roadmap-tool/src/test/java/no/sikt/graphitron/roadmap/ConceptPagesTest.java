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

    // --- items contract (R488) ---------------------------------------------

    @Test
    void extractItemIds_singleId() {
        assertThat(ConceptPages.extractItemIds("p.html",
            "<h1 data-concept-items=\"R458\">x</h1>", 500))
            .containsExactly("R458");
    }

    @Test
    void extractItemIds_multipleWithWhitespaceTrimmed() {
        assertThat(ConceptPages.extractItemIds("p.html",
            "<h1 data-concept-items=\" R12 , R7 ,R458 \">x</h1>", 500))
            .containsExactly("R12", "R7", "R458");
    }

    @Test
    void missingItemsAttribute_failsTheBuildNamingTheFile() {
        assertThatThrownBy(() -> ConceptPages.extractItemIds("broken.html",
            "<h1 data-concept-title=\"T\">no items</h1>", 500))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("broken.html");
    }

    @Test
    void blankItemsAttribute_failsTheBuild() {
        assertThatThrownBy(() -> ConceptPages.extractItemIds("blank.html",
            "<h1 data-concept-items=\"  \">x</h1>", 500))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("blank.html");
    }

    @Test
    void malformedItemsToken_failsTheBuild() {
        assertThatThrownBy(() -> ConceptPages.extractItemIds("bad.html",
            "<h1 data-concept-items=\"R458, nope\">x</h1>", 500))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("bad.html");
    }

    @Test
    void allocatedIdBound_idAtOrAboveNextId_fails() {
        assertThatThrownBy(() -> ConceptPages.extractItemIds("typo.html",
            "<h1 data-concept-items=\"R999\">x</h1>", 500))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("typo.html");
        // Boundary: id == next-id is not yet allocated.
        assertThatThrownBy(() -> ConceptPages.extractItemIds("boundary.html",
            "<h1 data-concept-items=\"R500\">x</h1>", 500))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void allocatedIdBound_idBelowNextId_passesEvenWithNoLiveItem() {
        // R458 shipped (file deleted on Done): below next-id, no item file, still legal.
        assertThat(ConceptPages.extractItemIds("shipped.html",
            "<h1 data-concept-items=\"R458\">x</h1>", 500))
            .containsExactly("R458");
    }

    @Test
    void kickerEnforcer_declaredIdAbsentFromKicker_failsNamingTheFile() {
        String html = "<p class=\"kicker\">Concept explainer · theme: x</p>"
            + "<h1 data-concept-items=\"R458\">x</h1>";
        assertThatThrownBy(() -> ConceptPages.enforceKicker("nokicker.html", html, List.of("R458")))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("nokicker.html");
    }

    @Test
    void kickerEnforcer_namingEachId_passes() {
        String html = "<p class=\"kicker\">Concept explainer · R12 · R458 · theme: x</p><h1>x</h1>";
        // Does not throw.
        ConceptPages.enforceKicker("ok.html", html, List.of("R12", "R458"));
    }

    // --- readPages (R488) ---------------------------------------------------

    private static String pageHtml(String title, String itemsAttr, String kicker) {
        return "<p class=\"kicker\">" + kicker + "</p>"
            + "<h1 data-concept-title=\"" + title + "\" data-concept-items=\"" + itemsAttr + "\">"
            + title + "</h1>";
    }

    private void changelog(int nextId) throws IOException {
        Files.writeString(roadmapDir.resolve("changelog.md"),
            "---\nnext-id: R" + nextId + "\n---\n");
    }

    @Test
    void readPages_returnsSlugSortedPagesWithTitleAndItemIds() throws IOException {
        changelog(500);
        Path concepts = Files.createDirectories(roadmapDir.resolve("concepts"));
        Files.writeString(concepts.resolve("zeta.html"),
            pageHtml("Zeta", "R7", "Concept explainer · R7 · theme: x"));
        Files.writeString(concepts.resolve("alpha.html"),
            pageHtml("Alpha", "R12, R458", "Concept explainer · R12 · R458 · theme: x"));
        Files.writeString(concepts.resolve("concepts.css"), "body{}");

        Map<String, ConceptPages.ConceptPage> pages = ConceptPages.readPages(roadmapDir);

        assertThat(pages.keySet()).containsExactly("alpha", "zeta");
        assertThat(pages.get("alpha").title()).isEqualTo("Alpha");
        assertThat(pages.get("alpha").itemIds()).containsExactly("R12", "R458");
        assertThat(pages.get("zeta").itemIds()).containsExactly("R7");
    }

    @Test
    void readPages_absentDirectory_isEmpty() throws IOException {
        assertThat(ConceptPages.readPages(roadmapDir)).isEmpty();
    }

    @Test
    void stage_rewritesPagesAndCopiesAssetsByteForByte() throws IOException {
        liveItem();
        changelog(500);
        Path concepts = Files.createDirectories(roadmapDir.resolve("concepts"));
        Files.writeString(concepts.resolve("page.html"),
            pageHtml("Page", "R1", "Concept explainer · R1 · theme: x")
                + "<a href=\"../live-item.md\">x</a>");
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
    void stage_enforcesItemsContractOnEveryStagedPage() throws IOException {
        changelog(500);
        Path concepts = Files.createDirectories(roadmapDir.resolve("concepts"));
        // Title present, items attribute missing: stage must fail naming the file.
        Files.writeString(concepts.resolve("broken.html"),
            "<p class=\"kicker\">Concept explainer · theme: x</p>"
                + "<h1 data-concept-title=\"Broken\">b</h1>");

        assertThatThrownBy(() -> ConceptPages.stage(roadmapDir, roadmapDir.resolve("out")))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("broken.html");
    }

    // --- ConceptIndex resolution (R488) -------------------------------------

    private static Main.Item item(String slug, String id, String status) {
        return new Main.Item(slug, id, slug, status, null, null, false, null, null,
            List.of(), null, null, "");
    }

    private static ConceptIndex index(List<Main.Item> items,
                                      Map<String, ConceptPages.ConceptPage> pages) {
        return ConceptIndex.of(items, new java.util.TreeMap<>(pages));
    }

    @Test
    void conceptIndex_resolvesLiveAndShippedAnchors() {
        var pages = Map.of(
            "alpha", new ConceptPages.ConceptPage("alpha", "Alpha", List.of("R1", "R458")));
        ConceptIndex idx = index(List.of(item("live-item", "R1", "In Progress")), pages);

        assertThat(idx.anchorsFor("alpha")).containsExactly(
            new ConceptIndex.Live("R1", "live-item"),
            new ConceptIndex.Shipped("R458"));
    }

    @Test
    void conceptIndex_reverseIndexIsSlugSortedPerItem() {
        var pages = new java.util.TreeMap<String, ConceptPages.ConceptPage>();
        pages.put("beta", new ConceptPages.ConceptPage("beta", "Beta", List.of("R1")));
        pages.put("alpha", new ConceptPages.ConceptPage("alpha", "Alpha", List.of("R1")));
        ConceptIndex idx = ConceptIndex.of(List.of(item("live-item", "R1", "In Progress")), pages);

        assertThat(idx.explainerSlugsFor("R1")).containsExactly("alpha", "beta");
        assertThat(idx.explainerSlugsFor("R999")).isEmpty();
    }

    // --- README rendering (R488) --------------------------------------------

    @Test
    void readmeActivePlanCell_gainsExplainerLinkWhenBacked() {
        Main.Item it = item("live-item", "R1", "In Progress");
        var pages = Map.of("alpha", new ConceptPages.ConceptPage("alpha", "Alpha", List.of("R1")));
        String md = Main.render(List.of(it), index(List.of(it), pages));

        assertThat(md).contains("[plan](live-item.md) · [explainer](concepts/alpha.html)");
        // Unbacked item: plain plan cell.
        assertThat(Main.render(List.of(it), ConceptIndex.empty()))
            .contains("[plan](live-item.md) |")
            .doesNotContain("explainer");
    }

    @Test
    void readmeBacklogLine_gainsParenthesizedExplainerLink() {
        Main.Item it = item("live-item", "R1", "Backlog");
        var pages = Map.of("alpha", new ConceptPages.ConceptPage("alpha", "Alpha", List.of("R1")));
        String md = Main.render(List.of(it), index(List.of(it), pages));

        assertThat(md).contains("[**live-item**](live-item.md) ([explainer](concepts/alpha.html))");
    }

    @Test
    void readmeActivePlanCell_twoPagesBackingOneItem_emitBothInSlugOrder() {
        Main.Item it = item("live-item", "R1", "In Progress");
        var pages = new java.util.TreeMap<String, ConceptPages.ConceptPage>();
        pages.put("zeta", new ConceptPages.ConceptPage("zeta", "Zeta", List.of("R1")));
        pages.put("alpha", new ConceptPages.ConceptPage("alpha", "Alpha", List.of("R1")));
        String md = Main.render(List.of(it), ConceptIndex.of(List.of(it), pages));

        assertThat(md).contains(
            "[plan](live-item.md) · [explainer](concepts/alpha.html) · [explainer](concepts/zeta.html)");
    }

    @Test
    void readmeConceptListing_annotatesLiveAndShippedAnchors() {
        Main.Item it = item("live-item", "R1", "In Progress");
        var pages = new java.util.TreeMap<String, ConceptPages.ConceptPage>();
        pages.put("live-page", new ConceptPages.ConceptPage("live-page", "Live title", List.of("R1")));
        pages.put("shipped-page", new ConceptPages.ConceptPage("shipped-page", "Shipped title", List.of("R458")));
        String md = Main.render(List.of(it), ConceptIndex.of(List.of(it), pages));

        assertThat(md).contains("## Concept explainers");
        assertThat(md).contains("- [Live title](concepts/live-page.html) (backs [R1](live-item.md))");
        assertThat(md).contains("- [Shipped title](concepts/shipped-page.html) (backs R458)");
    }

    @Test
    void readmeListing_omittedWhenNoPages() {
        Main.Item it = item("some-item", "R1", "In Progress");
        assertThat(Main.render(List.of(it), ConceptIndex.empty()))
            .isEqualTo(Main.render(List.of(it)))
            .doesNotContain("Concept explainers");
    }

    @Test
    void readmeListing_driftsWhenDeclarationChanges() {
        Main.Item it = item("live-item", "R1", "In Progress");
        var backsR1 = Map.of("alpha", new ConceptPages.ConceptPage("alpha", "Alpha", List.of("R1")));
        var backsR458 = Map.of("alpha", new ConceptPages.ConceptPage("alpha", "Alpha", List.of("R458")));

        // Changing a page's declaration changes the rendered README, so verify's
        // byte comparison fails on unregenerated listings.
        assertThat(Main.render(List.of(it), index(List.of(it), backsR1)))
            .isNotEqualTo(Main.render(List.of(it), index(List.of(it), backsR458)));
    }

    // --- status board rendering (R488) --------------------------------------

    @Test
    void statusBoardActivePlanCell_gainsExplainerLinkMacro() {
        Main.Item it = item("live-item", "R1", "In Progress");
        var pages = Map.of("alpha", new ConceptPages.ConceptPage("alpha", "Alpha", List.of("R1")));
        String board = Main.renderAdocStatusBoard(List.of(it), index(List.of(it), pages));

        assertThat(board).contains(
            "xref:plans/live-item.adoc[plan] · link:concepts/alpha.html[explainer]");
    }

    @Test
    void statusBoardConceptListing_annotatesLiveAndShippedAnchors() {
        Main.Item it = item("live-item", "R1", "In Progress");
        var pages = new java.util.TreeMap<String, ConceptPages.ConceptPage>();
        pages.put("live-page", new ConceptPages.ConceptPage("live-page", "Live title", List.of("R1")));
        pages.put("shipped-page", new ConceptPages.ConceptPage("shipped-page", "Shipped title", List.of("R458")));
        String board = Main.renderAdocStatusBoard(List.of(it), ConceptIndex.of(List.of(it), pages));

        assertThat(board).contains("== Concept explainers");
        assertThat(board).contains(
            "* link:concepts/live-page.html[Live title] (backs xref:plans/live-item.adoc[R1])");
        assertThat(board).contains(
            "* link:concepts/shipped-page.html[Shipped title] (backs R458)");
    }

    @Test
    void statusBoardListing_omittedWhenNoPages() {
        Main.Item it = item("some-item", "R1", "In Progress");
        assertThat(Main.renderAdocStatusBoard(List.of(it), ConceptIndex.empty()))
            .isEqualTo(Main.renderAdocStatusBoard(List.of(it)))
            .doesNotContain("Concept explainers");
    }
}
