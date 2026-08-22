package no.sikt.graphitron.roadmap;

import no.sikt.graphitron.model.catalog.StoreProse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the drift guard's resolution line with synthetic universes, its failure modes with
 * fixture trees, and the real corpus against the real store: the check is only worth its keep
 * if the tree it guards satisfies it.
 */
class SchemaIdentifierDriftCheckTest {

    private static final SchemaIdentifierDriftCheck.Universe UNIVERSE =
        new SchemaIdentifierDriftCheck.Universe(
            Set.of("graphql_"),
            Map.of("graphql_field", Set.of("type_sdl")));

    @Test
    void resolvesPrefixRelationAndColumn() {
        assertThat(SchemaIdentifierDriftCheck.scan("p.adoc",
            "The `graphql_` family, its `graphql_field` relation, and `graphql_field.type_sdl`.",
            UNIVERSE)).isEmpty();
    }

    @Test
    void flagsARenamedRelationAndAStrayColumn() {
        var findings = SchemaIdentifierDriftCheck.scan("p.adoc",
            "A `graphql_fields` relation and `graphql_field.type_sdl_old`.\n", UNIVERSE);
        assertThat(findings).extracting(SchemaIdentifierDriftCheck.Finding::identifier)
            .containsExactly("graphql_fields", "graphql_field.type_sdl_old");
        assertThat(findings).extracting(SchemaIdentifierDriftCheck.Finding::line)
            .containsExactly(1, 1);
    }

    @Test
    void ignoresIdentifiersOutsideEveryFamilyAndMultiTokenSpans() {
        assertThat(SchemaIdentifierDriftCheck.scan("p.adoc",
            "The `graph_name` column, the `docs` module, `graphitron-model.sql`, and the span"
                + " `SELECT graphql_bogus FROM somewhere` are not this check's to police.",
            UNIVERSE)).isEmpty();
    }

    @Test
    void skipsVerbatimBlocksAndUnwrapsTheInertForm() {
        assertThat(SchemaIdentifierDriftCheck.scan("p.adoc", """
            ----
            `graphql_retired_in_a_listing`
            ----
            An inert `+graphql_field+` span resolves.
            """, UNIVERSE)).isEmpty();
    }

    @Test
    void storeProseResolvesBareCitations() {
        assertThat(SchemaIdentifierDriftCheck.scanStoreProse(
            prose("The graphql_ family, its graphql_field relation, and graphql_field.type_sdl."),
            UNIVERSE)).isEmpty();
    }

    @Test
    void storeProseSeesARelationNamedAtTheEndOfASentence() {
        assertThat(SchemaIdentifierDriftCheck.scanStoreProse(
            prose("Stated once on graphql_gone. The rest follows."), UNIVERSE))
            .extracting(SchemaIdentifierDriftCheck.Finding::identifier)
            .containsExactly("t: graphql_gone");
    }

    /**
     * The extractor's two hazards, both real in the corpus: a package-qualified class name whose
     * tail segments would resolve as nothing, and words that merely start the way a family does.
     * A tail is unreachable because a match may not begin after a dot; a hyphenated word is out
     * because a family prefix ends in an underscore and {@code store-native} does not.
     */
    @Test
    void storeProseIgnoresQualifiedTailsAndOrdinaryWords() {
        assertThat(SchemaIdentifierDriftCheck.scanStoreProse(
            prose("Derived by no.sikt.graphitron.roadmap.Main, store-native, from a graph_name"
                + " column, per the graphql spec."), UNIVERSE)).isEmpty();
    }

    /** Both corpora at once: {@code run} scans the authored pages and the store's own prose. */
    @Test
    void theTreeAndTheStoreProseItGuardsSatisfyIt() throws IOException {
        assertThat(SchemaIdentifierDriftCheck.run(List.of(repoRoot().toString()))).isZero();
    }

    @Test
    void run_withFindings_throwsBuildFailure(@TempDir Path dir) throws IOException {
        writePage(dir, "The `graphql_definitely_retired` relation still cited here.\n");
        assertThatThrownBy(() -> SchemaIdentifierDriftCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_clean_returnsZero(@TempDir Path dir) throws IOException {
        writePage(dir, "The `graphql_field` relation of the `graphql_` family.\n");
        assertThat(SchemaIdentifierDriftCheck.run(List.of(dir.toString()))).isZero();
    }

    @Test
    void run_usageError_returnsExitCodeWithoutThrowing() throws IOException {
        assertThat(SchemaIdentifierDriftCheck.run(List.of())).isEqualTo(64);
    }

    @Test
    void run_missingTree_throwsBuildFailure(@TempDir Path dir) {
        // The floor against a vacuous pass: a moved or renamed habitat must fail the check
        // rather than quietly scanning nothing and reporting all clear.
        assertThatThrownBy(() -> SchemaIdentifierDriftCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    private static List<StoreProse.Entry> prose(String text) {
        return List.of(new StoreProse.Entry(StoreProse.Kind.RELATION_COMMENT, "t", text));
    }

    private static void writePage(Path root, String content) throws IOException {
        Path tree = root.resolve(SchemaIdentifierDriftCheck.SCANNED_TREE);
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("page.adoc"), content);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("CLAUDE.md"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("no CLAUDE.md above " + Path.of("").toAbsolutePath());
        }
        return dir;
    }
}
