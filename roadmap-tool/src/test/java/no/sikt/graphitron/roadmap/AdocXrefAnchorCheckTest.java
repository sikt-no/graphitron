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
 * Pins the cross-file xref contract: which spans and blocks yield a reference (anchored or
 * not), which declaration forms publish an anchor, which source provenance turns a wrong path
 * into a failure rather than a count, which staged path maps back to which authored source,
 * and the one under-report the same-line detection rule knowingly accepts.
 */
class AdocXrefAnchorCheckTest {

    private static final Path PAGE = Path.of("/staging/manual/page.adoc");

    // ===== What counts as a reference =====

    @Test
    void relativeTarget_isCollected() {
        // 15 of the 27 cross-file references in this corpus are ../-relative, including one of
        // the four the gate was written to catch, so keying detection on the target's opening
        // character would skip the majority of the population.
        List<AdocXrefAnchorCheck.Reference> refs = AdocXrefAnchorCheck.collectFrom(PAGE,
            "See xref:../../tutorial/05-mutations.adoc#grouping[Grouping].\n");

        assertThat(refs).singleElement().satisfies(r -> {
            assertThat(r.targetPath()).isEqualTo("../../tutorial/05-mutations.adoc");
            assertThat(r.anchor()).isEqualTo("grouping");
            assertThat(r.line()).isEqualTo(1);
        });
    }

    @Test
    void sameFileReference_isNotCollected() {
        // Asciidoctor already reports these at INFO; the unreported cross-file class is what
        // this check exists for.
        assertThat(AdocXrefAnchorCheck.collectFrom(PAGE, "See xref:#session-identity[Session].\n"))
            .isEmpty();
    }

    @Test
    void unanchoredCrossFileTarget_isCollected() {
        // A plain page reference dangles just as silently as an anchored one when the page
        // moves; the widened population carries a null anchor and gets the path check only.
        List<AdocXrefAnchorCheck.Reference> refs = AdocXrefAnchorCheck.collectFrom(PAGE,
            "See xref:../how-to/dev-loop.adoc[the dev loop].\n");

        assertThat(refs).singleElement().satisfies(r -> {
            assertThat(r.targetPath()).isEqualTo("../how-to/dev-loop.adoc");
            assertThat(r.anchor()).isNull();
        });
    }

    @Test
    void nonAdocAndAttributeBearingTargets_areNotCollected() {
        // An attribute-bearing target resolves only after a substitution this check does not
        // perform, and a non-.adoc target is not a page staging can vouch for.
        String adoc = "See xref:{site-url}/page.adoc[attr] and xref:diagram.svg[image].\n";

        assertThat(AdocXrefAnchorCheck.collectFrom(PAGE, adoc)).isEmpty();
    }

    @Test
    void referenceInATableCell_isCollected() {
        // Live at manual/reference/mojo-configuration.adoc: the cell is prose that publishes a
        // link like any other, so the table block is deliberately not skipped.
        String adoc = """
            |===
            | `compile`
            | See xref:../how-to/dev-loop.adoc#compiled-classes[Compiled classes].
            |===
            """;

        assertThat(AdocXrefAnchorCheck.collectFrom(PAGE, adoc))
            .singleElement()
            .satisfies(r -> assertThat(r.anchor()).isEqualTo("compiled-classes"));
    }

    @Test
    void referenceInsideAListingOrCommentBlock_isNotCollected() {
        String adoc = """
            ----
            xref:node.adoc#in-a-listing[label]
            ----

            ////
            xref:node.adoc#in-a-comment[label]
            ////
            """;

        assertThat(AdocXrefAnchorCheck.collectFrom(PAGE, adoc)).isEmpty();
    }

    @Test
    void bareBacktickSpan_isCollected_bothInertForms_areNot() {
        // A markdown code span is literal by definition; a single-backtick AsciiDoc span is
        // not, and publishes a live link. The collector reads InertSpans' definition of inert
        // rather than carrying a second list that drifts the next time the emitter gains a form.
        String adoc = "A `xref:node.adoc#live[l]`, a `+xref:node.adoc#plus[l]+`"
            + " and a `pass:c[xref:node.adoc#macro[l\\]]` span.\n";

        assertThat(AdocXrefAnchorCheck.collectFrom(PAGE, adoc))
            .singleElement()
            .satisfies(r -> assertThat(r.anchor()).isEqualTo("live"));
    }

    @Test
    void bareTargetWhoseAttrlistSitsOnALaterLine_isNotCollected() {
        // The known under-report, asserted rather than discovered later. Asciidoctor's inline
        // match spans the gap and builds one macro out of the two quotes; a same-line rule sees
        // only the bracketed one. Under-reporting here is the accepted trade: modelling the
        // cross-span match would be the id-algorithm mistake in another costume.
        String adoc = """
            A bare `+xref:node.adoc#anchor+` forms no macro on its own, but a bracketed
            `+xref:<file>.adoc#anchor[label]+` two lines further down activates it.
            """;

        assertThat(AdocXrefAnchorCheck.collectFrom(PAGE, adoc)).isEmpty();
    }

    // ===== What publishes an anchor =====

    @Test
    void bothDeclarationForms_publishAnAnchor() {
        // [#id] and [[id]] are both live in this tree, so a collector reading only the
        // shorthand would report a working link as dangling: a false failure, the direction
        // this check treats as the worse one.
        String adoc = """
            [#kind-author-error]
            == Author error

            [[chain]]
            == Chaining

            [[reftext-form,Named]]
            == With reftext
            """;

        assertThat(AdocXrefAnchorCheck.anchorsIn(adoc))
            .containsExactly("kind-author-error", "chain", "reftext-form");
    }

    @Test
    void autoGeneratedHeadingId_doesNotPublishAnAnchor() {
        // The rule in one assertion: resolving against explicit anchors only is what keeps this
        // check from owning a copy of Asciidoctor's id-generation algorithm.
        assertThat(AdocXrefAnchorCheck.anchorsIn("== Several node types over one table\n")).isEmpty();
    }

    @Test
    void quotedAnchorDeclaration_doesNotPublishAnAnchor() {
        assertThat(AdocXrefAnchorCheck.anchorsIn("Declare it as `+[[id]]+` or `+[#id]+`.\n")).isEmpty();
    }

    // ===== End to end =====

    @Test
    void danglingAnchor_failsTheBuild(@TempDir Path root) throws IOException {
        writeTree(root);
        Files.writeString(root.resolve("manual/nodeId.adoc"),
            "See xref:node.adoc#_several_node_types_over_one_table[several node types].\n");

        assertThatThrownBy(() -> AdocXrefAnchorCheck.run(List.of(root.toString())))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("target pages or anchors do not exist");
    }

    @Test
    void underscoreFormAgainstAKebabAnchor_isTheRecurringMistake(@TempDir Path root) throws IOException {
        // The id-format mismatch this gate was written for: the site renders with idseparator
        // '-', so the underscore form an author gets from Asciidoctor's own documentation looks
        // right, resolves to nothing, and renders as a working link to the top of the page.
        writeTree(root);
        Files.writeString(root.resolve("manual/nodeId.adoc"),
            "See xref:node.adoc#_several-node-types-over-one-table[several node types].\n");

        assertThatThrownBy(() -> AdocXrefAnchorCheck.run(List.of(root.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void resolvingAnchor_passes(@TempDir Path root) throws IOException {
        writeTree(root);
        Files.writeString(root.resolve("manual/nodeId.adoc"),
            "See xref:node.adoc#several-node-types-over-one-table[several node types].\n");

        assertThat(AdocXrefAnchorCheck.run(List.of(root.toString()))).isZero();
    }

    @Test
    void danglingPathInRoadmapProse_isCountedNotFailed(@TempDir Path root) throws IOException {
        // Neither silently passed nor failed: item bodies quote example paths, and failing on
        // a wrong path there would turn every quoted example into a build break.
        writeTree(root);
        Files.createDirectories(root.resolve("roadmap/plans"));
        Files.writeString(root.resolve("roadmap/plans/some-item.adoc"), """
            See xref:no-such-page.adoc#anchor[label].
            And xref:../../../outside.adoc[label].
            """);

        assertThat(AdocXrefAnchorCheck.run(List.of(root.toString()))).isZero();
    }

    @Test
    void danglingPathOnADocsAuthoredPage_failsTheBuild(@TempDir Path root) throws IOException {
        // On a published docs page a wrong path is an authoring defect with nothing to excuse
        // it: the link 404s on the site. Anchored or not, it fails.
        writeTree(root);
        Files.writeString(root.resolve("manual/nodeId.adoc"),
            "See xref:no-such-page.adoc[label].\n");

        assertThatThrownBy(() -> AdocXrefAnchorCheck.run(List.of(root.toString())))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("target pages or anchors do not exist");
    }

    @Test
    void danglingPathOnAGeneratedBoard_failsTheBuild(@TempDir Path root) throws IOException {
        // The status boards are emitted from front-matter, not authored prose: a wrong path
        // there is an emitter defect, exactly the class an authored-tree walker cannot see.
        writeTree(root);
        Files.createDirectories(root.resolve("roadmap"));
        Files.writeString(root.resolve("roadmap/index.adoc"),
            "See xref:../architecture/no-such-page.adoc[label].\n");

        assertThatThrownBy(() -> AdocXrefAnchorCheck.run(List.of(root.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void scanBelowTheFloor_failsTheBuild(@TempDir Path root) throws IOException {
        // The widened population is pinned against vacuity: a collector regression that stops
        // seeing references must fail, not pass an empty scan.
        writeTree(root);
        Files.writeString(root.resolve("manual/nodeId.adoc"),
            "See xref:node.adoc#several-node-types-over-one-table[label].\n");

        assertThat(AdocXrefAnchorCheck.run(List.of(root.toString(), "1"))).isZero();
        assertThatThrownBy(() -> AdocXrefAnchorCheck.run(List.of(root.toString(), "2")))
            .isInstanceOf(BuildFailure.class)
            .hasMessageContaining("below the floor");
    }

    @Test
    void usageAndNonDirectoryRoots_returnSixtyFour(@TempDir Path root) throws IOException {
        // CLI dev errors keep returning 64 rather than throwing; only the verify-phase tripwire
        // needs to survive as a BUILD FAILURE.
        assertThat(AdocXrefAnchorCheck.run(List.of())).isEqualTo(64);
        assertThat(AdocXrefAnchorCheck.run(List.of(root.resolve("nope").toString()))).isEqualTo(64);
    }

    // ===== Provenance =====

    @Test
    void stagedPathsMapBackToTheirAuthoredSource() {
        // All of staging is build output an author cannot edit, and it is populated three ways.
        assertThat(AdocXrefAnchorCheck.authoredSource("manual/reference/directives/node.adoc"))
            .contains("docs/manual/reference/directives/node.adoc");
        assertThat(AdocXrefAnchorCheck.authoredSource("README.adoc"))
            .contains("docs/README.adoc");
        assertThat(AdocXrefAnchorCheck.authoredSource("roadmap/plans/some-item.adoc"))
            .contains("roadmap/some-item.md");
        assertThat(AdocXrefAnchorCheck.authoredSource("roadmap/changelog.adoc"))
            .contains("roadmap/changelog.md");
        assertThat(AdocXrefAnchorCheck.authoredSource("roadmap/inference-axis-coverage.adoc"))
            .contains("roadmap/inference-axis-coverage.adoc");
    }

    @Test
    void statusBoards_haveNoSingleAuthoredSource() {
        // Written from item front-matter; naming a file here would name one that does not exist.
        assertThat(AdocXrefAnchorCheck.authoredSource("roadmap/index.adoc")).isEmpty();
        assertThat(AdocXrefAnchorCheck.authoredSource("roadmap/by-theme.adoc")).isEmpty();
    }

    /** A minimal staged tree whose one target page publishes one explicit anchor. */
    private static void writeTree(Path root) throws IOException {
        Files.createDirectories(root.resolve("manual"));
        Files.writeString(root.resolve("manual/node.adoc"), """
            = Node

            [#several-node-types-over-one-table]
            === Several node types over one table
            """);
    }
}
