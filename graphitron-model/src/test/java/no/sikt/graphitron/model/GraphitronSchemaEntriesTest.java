package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_LINK_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_LINK_IMPORT_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The schema site's decode: federation's opt-in, read where it was written.
 *
 * <p>The claims worth standing up here are the ones this site decides. Its import list is the one
 * argument in the family whose elements admit two spellings, so that both land in one relation and
 * in the order the author wrote them is the thing to pin. And a repeatable directive applied twice
 * on one schema is two rows keyed by position with no ordinal assigned, which is what keying on the
 * at sign buys and what the site has no coordinate to do instead.
 */
class GraphitronSchemaEntriesTest {

    private static final String GRAPH = "schema-site";

    /** What every case here writes above its own schema block: the vocabulary a federated consumer declares. */
    private static final String FEDERATION = """
        scalar link__Import
        directive @link(url: String, import: [link__Import]) repeatable on SCHEMA
        type Query { films: [Film!] }
        type Film { title: String }
        """;

    /**
     * The two spellings are one fact with the alias absent in the first, so they are one relation
     * and not two, and the index each element was written at is what orders them. Reading them back
     * in position order is the whole claim: the bare string and the aliased object arrive from two
     * separate readings inside the writer, and nothing downstream should be able to tell.
     */
    @Test
    @DisplayName("both import spellings land in one relation, in written order")
    void bothImportSpellingsLandInOneRelation(@TempDir Path tmp) {
        write(tmp, "schema.graphqls", FEDERATION + """
            extend schema @link(
              url: "https://specs.apollo.dev/federation/v2.10"
              import: ["@key", {name: "@shareable", as: "@federatedShareable"}, "@external"]
            )
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_AST_LINK_IMPORT_ENTRY;

            assertThat(dsl.select(t.POSITION, t.NAME, t.ALIAS).from(t).orderBy(t.POSITION).fetch())
                .as("a bare name, an aliased object and a bare name after it, at their own indices")
                .extracting(row -> row.value1(), row -> row.value2(), row -> row.value3())
                .containsExactly(
                    tuple(0, "@key", null),
                    tuple(1, "@shareable", "@federatedShareable"),
                    tuple(2, "@external", null));

            assertThat(dsl.select(GRAPHITRON_AST_LINK_ENTRY.URL).from(GRAPHITRON_AST_LINK_ENTRY)
                    .fetchOne(GRAPHITRON_AST_LINK_ENTRY.URL))
                .as("and the application they hang off carries the specification it opted in to")
                .isEqualTo("https://specs.apollo.dev/federation/v2.10");
        });
    }

    /**
     * The directive repeats and the site has no coordinate to number applications at, so the
     * position of the at sign is the key. Two applications are two rows with no ordinal assigned
     * here, the numbering being the anchors' work over rows that carry what it would sort by.
     */
    @Test
    @DisplayName("a repeatable application needs no ordinal, the at sign being the key")
    void twoApplicationsAreTwoRowsWithoutAnOrdinal(@TempDir Path tmp) {
        write(tmp, "schema.graphqls", FEDERATION + """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
            extend schema @link(url: "https://example.com/other/v1.0", import: ["@other"])
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_AST_LINK_ENTRY;

            assertThat(dsl.select(t.URL).from(t).orderBy(t.SOURCE_LINE).fetch(t.URL))
                .as("two applications, told apart by where they were written")
                .containsExactly("https://specs.apollo.dev/federation/v2.10",
                    "https://example.com/other/v1.0");

            assertThat(dsl.fetchCount(GRAPHITRON_AST_LINK_IMPORT_ENTRY))
                .as("and each keeps its own imports, both at position 0 under different parents")
                .isEqualTo(2);
        });
    }

    /**
     * An application with no import list is still the fact that the schema opted in, so the parent
     * row exists with no children. The row is written for every application rather than only for
     * those that wrote an argument, which is what lets the child relation hang off it at all.
     */
    @Test
    @DisplayName("an application with no imports is still a row")
    void anApplicationWithoutImportsIsStillARow(@TempDir Path tmp) {
        write(tmp, "schema.graphqls", FEDERATION + """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10")
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.fetchCount(GRAPHITRON_AST_LINK_ENTRY))
                .as("the opt-in is a fact whether or not anything was imported").isEqualTo(1);
            assertThat(dsl.fetchCount(GRAPHITRON_AST_LINK_IMPORT_ENTRY))
                .as("and nothing hangs off it").isZero();
        });
    }

    /**
     * An element that names nothing spends its index, which keeps the elements after it at the
     * positions the author would count. Filling the gap instead would silently renumber the list.
     */
    @Test
    @DisplayName("an element that names nothing still spends its index")
    void anUnnamedElementDoesNotRenumberTheOnesAfterIt(@TempDir Path tmp) {
        write(tmp, "schema.graphqls", FEDERATION + """
            extend schema @link(
              url: "https://specs.apollo.dev/federation/v2.10"
              import: [{as: "@onlyAnAlias"}, "@key"]
            )
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_AST_LINK_IMPORT_ENTRY;

            assertThat(dsl.select(t.POSITION, t.NAME).from(t).fetch())
                .as("the object naming nothing writes no row, and the name after it is still at 1")
                .extracting(row -> row.value1(), row -> row.value2())
                .containsExactly(tuple(1, "@key"));
        });
    }

    /** One reading of everything the directory holds, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir) {
        SdlCapture.capture(dsl, new GraphIdentity(GRAPH, baseDir),
            SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            LocalDateTime.now());
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
