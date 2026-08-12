package no.sikt.graphitron.roadmap;

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
    void theTreeItGuardsSatisfiesIt() throws IOException {
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
