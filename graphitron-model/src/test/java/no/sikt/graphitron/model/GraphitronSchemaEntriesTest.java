package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_COMPOSE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_LINK_IMPORT_ENTRY;
import static no.sikt.graphitron.model.test.ElementOrder.writtenAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The schema site's decode where it tolerates what the generator refuses. What the site writes for a
 * schema the generator accepts is stated by the fact documents {@code scenarios/federation-link},
 * {@code scenarios/node-type-directive} and {@code scenarios/node-type-directive-namespaced}: each
 * {@code @link} application a row, the one importing nothing included, both import spellings in one
 * relation, both {@code @composeDirective} spellings in one relation, and the sweep of one the author
 * removed. The two cases here write a list element or an application that names nothing, which no
 * document can hold, so they stay inline.
 */
class GraphitronSchemaEntriesTest {

    /**
     * The case's own graph, named for its method, so a store several cases capture into holds each
     * case's rows apart; every read below filters to it.
     */
    private final String graph;

    GraphitronSchemaEntriesTest(TestInfo info) {
        graph = "schemaEntries." + info.getTestMethod().orElseThrow().getName();
    }

    /**
     * What every case here writes above its own schema block. No federation declaration: a
     * federated consumer writes none, the {@code @link} importing them.
     */
    private static final String FEDERATION = """
        type Query { films: [Film!] }
        type Film { title: String }
        """;

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

        ThreadConfinedStore.run(dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_AST_LINK_IMPORT_ENTRY;

            assertThat(dsl.select(writtenAt(t), t.NAME).from(t)
                    .where(t.GRAPH_NAME.eq(graph)).fetch())
                .as("the object naming nothing writes no row, and the name after it is still at 1")
                .extracting(row -> row.value1(), row -> row.value2())
                .containsExactly(tuple(1, "@key"));
        });
    }

    /**
     * The argument is required, so an application without a string name asks the supergraph for
     * nothing a row could hold. The applied-directive row already says it was applied; a row here
     * would hold its key and nothing else.
     */
    @Test
    @DisplayName("an @composeDirective that names no string writes no row")
    void aComposeDirectiveNamingNothingWritesNoRow(@TempDir Path tmp) {
        write(tmp, "schema.graphqls", FEDERATION + """
            extend schema
              @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@composeDirective"])
              @composeDirective(name: 7)
              @composeDirective
            """);

        ThreadConfinedStore.run(dsl -> {
            read(dsl, tmp);

            assertThat(dsl.fetchCount(GRAPHITRON_AST_COMPOSE_DIRECTIVE_ENTRY,
                    GRAPHITRON_AST_COMPOSE_DIRECTIVE_ENTRY.GRAPH_NAME.eq(graph)))
                .as("a name that is not a string, and no name at all").isZero();
        });
    }

    /** One reading of everything the directory holds, which is what a run does. */
    private void read(DSLContext dsl, Path baseDir) {
        var graph = new GraphIdentity(this.graph, baseDir);
        var config = SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
        var readAt = LocalDateTime.now();
        ModelCapture.writeGraph(dsl, graph, readAt);
        var reading = GraphQLSourceCapture.capture(dsl, graph, config, readAt);
        var documents = reading.documents();
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
        GraphQLAssemblyCapture.capture(dsl, graph, reading, readAt);
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
