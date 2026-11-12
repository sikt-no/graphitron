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
import org.jooq.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The field site's anchors, derived rather than walked.
 *
 * <p>Four directives the specification admits at {@code FIELD_DEFINITION} and nowhere else moved
 * here, which is what lets each of them derive from one entry relation. A directive also legal on
 * an input object's field has its applications split across two, an input object's field being an
 * input value in the entry relations rather than a field, and its anchor is a union with a
 * parent-kind test rather than a select. That distinction is the reason this batch is these four.
 *
 * <p>What the cases are about is the same two things the type site's are. The choosing, which here
 * has to reach through two declarations to find merge order. And the absence, where a join's
 * polarity is the relation's own shape.
 */
class GraphitronFieldAnchorsTest {

    private static final String GRAPH = "field-anchors";

    /**
     * The polarity at a site where every column is optional because the directive deduces
     * what the author leaves out. A bare application is still a request for a connection, so the
     * row exists and says nothing, which is the difference between deducing a page size and not
     * having been asked for a connection at all.
     */
    @Test
    @DisplayName("a bare connection request is a row that states nothing")
    void aBareConnectionRequestIsARowStatingNothing(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            type Query {
              films: [Film!] @asConnection
              actors: [Film!] @asConnection(defaultFirstValue: 25)
              plain: [Film!]
            }
            type Film { title: String }
            """);

        ThreadConfinedStore.run(dsl -> {
            read(dsl, tmp);
            var a = GRAPHQL_DIRECTIVE_APPLICATION;
            var f = GRAPHQL_FIELD_ELEMENT;
            var e = GRAPHITRON_AST_CONNECTION_ENTRY;
            assertThat(dsl.select(f.FIELD_NAME, e.DEFAULT_FIRST_VALUE).from(a)
                    .join(f).on(f.GRAPH_NAME.eq(a.GRAPH_NAME), f.COORDINATE.eq(a.COORDINATE))
                    .leftJoin(e).on(e.GRAPH_NAME.eq(a.GRAPH_NAME), e.SOURCE_NAME.eq(a.SOURCE_NAME),
                        e.SOURCE_LINE.eq(a.SOURCE_LINE), e.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
                    .where(a.GRAPH_NAME.eq(GRAPH), a.DIRECTIVE_NAME.eq("asConnection"))
                    .orderBy(f.FIELD_NAME).fetch()
                    .map(Record::intoList))
                .as("both applications are rows and the bare one carries no page size; the field "
                    + "that applied nothing has no row, which is what tells the two apart")
                .containsExactly(List.of("actors", 25), Arrays.asList("films", null));
        });
    }

    /**
     * The choosing, at the grain that makes it reachable. A field declared twice is retained by the
     * registry rather than refused, so one coordinate can carry two applications, and a
     * coordinate-keyed anchor admits one of them. Merge order picks it, which is the declaration's
     * order and not the file's.
     */
    @Test
    @DisplayName("a field declared twice keeps the base declaration's application")
    void theBaseDeclarationWinsARedeclaredField(@TempDir Path tmp) {
        write(tmp, "corpus.graphqls", """
            extend type Query { films: [Film!] @asConnection(defaultFirstValue: 99) }

            type Query { films: [Film!] @asConnection(defaultFirstValue: 25) }

            type Film { title: String }
            """);

        ThreadConfinedStore.run(dsl -> {
            read(dsl, tmp);
            var e = GRAPHITRON_AST_CONNECTION_ENTRY;
            assertThat(dsl.select(e.DEFAULT_FIRST_VALUE).from(e).where(e.GRAPH_NAME.eq(GRAPH))
                    .fetch(e.DEFAULT_FIRST_VALUE))
                .as("both declarations reached capture and both applications are entries; without "
                    + "this the case below could hold because the registry kept only one")
                .containsExactlyInAnyOrder(25, 99);
            var a = GRAPHQL_DIRECTIVE_APPLICATION;
            assertThat(dsl.select(e.DEFAULT_FIRST_VALUE).from(a)
                    .join(e).on(e.GRAPH_NAME.eq(a.GRAPH_NAME), e.SOURCE_NAME.eq(a.SOURCE_NAME),
                        e.SOURCE_LINE.eq(a.SOURCE_LINE), e.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
                    .where(a.GRAPH_NAME.eq(GRAPH), a.DIRECTIVE_NAME.eq("asConnection"),
                        a.ORDINAL.eq(0))
                    .fetch(e.DEFAULT_FIRST_VALUE))
                .as("the first application is the base declaration's, however the file is laid out")
                .containsExactly(25);
        });
    }

    /**
     * The facts this gatherer writes, without the assembly that raises the problem rows. The
     * corpora above are ones an author can write and a toolchain reports on, and capture states
     * what they say either way; asking for the problems too would mean assembling first, which is
     * a different question from what the rows are.
     */
    private static void read(DSLContext dsl, Path baseDir) {
        var graph = new GraphIdentity(GRAPH, baseDir);
        var config = SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
        var readAt = LocalDateTime.now();
        ModelCapture.writeGraph(dsl, graph, readAt);
        var reading = GraphQLSourceCapture.capture(dsl, graph, config, readAt);
        var documents = reading.documents();
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
