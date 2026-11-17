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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Deprecation, which is one fact with two markers and no way to ask a registry for it.
 *
 * <p>GraphQL forbids {@code @deprecated} on a directive definition and admits it on that
 * definition's arguments, so graphitron says the first with a token in the description. Both are
 * captured rather than recognised at read time, which is the difference these cases are about: a
 * consumer asks the store which directives are deprecated instead of matching a pattern against a
 * description it fetched for another reason.
 *
 * <p>What a single reading writes is stated by the fact document
 * {@code scenarios/directive-deprecation}: the docstring marker deprecating a directive with its
 * prose as the reason, a token inside a word marking nothing, an argument's own marker under its
 * coordinate with an empty reason where none was written, and the bundled vocabulary's two. The cases
 * here are the edits between two readings, which the sweep and the cascade answer.
 */
class GraphitronAnchorTest {

    /**
     * The case's own graph, named for its method, so a store several cases capture into holds each
     * case's rows apart; every read below filters to it.
     */
    private final String graph;

    GraphitronAnchorTest(TestInfo info) {
        graph = "deprecations." + info.getTestMethod().orElseThrow().getName();
    }

    /**
     * What the author withdrew goes. The marker is a token in prose and an argument's directive,
     * so an edit that removes either leaves a row no upsert can find: only the sweep reaches it.
     */
    @Test
    @DisplayName("a marker the author removed is not a fact any more")
    void aWithdrawnMarkerIsSwept(@TempDir Path tmp) {
        write(tmp, "vocabulary.graphqls", """
            "@deprecated use something else"
            directive @old(legacy: String @deprecated(reason: "gone")) on FIELD_DEFINITION

            type Query { a: String }
            """);

        ThreadConfinedStore.run(dsl -> {
            read(dsl, tmp);
            assertThat(marked(dsl, "old")).as("before").isTrue();
            assertThat(markedArgument(dsl, "old", "legacy")).as("before").isTrue();

            write(tmp, "vocabulary.graphqls", """
                "a directive that is no longer going anywhere"
                directive @old(legacy: String) on FIELD_DEFINITION

                type Query { a: String }
                """);
            read(dsl, tmp);

            assertThat(marked(dsl, "old"))
                .as("the token is gone from the description, so the directive is not deprecated")
                .isFalse();
            assertThat(markedArgument(dsl, "old", "legacy"))
                .as("and the argument's own marker went with the edit that removed it")
                .isFalse();
        });
    }

    /**
     * The half of a withdrawal no cascade can reach. The type still stands, so its coordinate is
     * still there and nothing is deleted out from under this row; what went is the application on
     * it, which leaves a row an upsert cannot find because there is no incoming row to match. Only
     * the sweep collects it, and until these relations carried an instant there was nothing to
     * sweep by and a pass emptied the partition instead.
     */
    @Test
    @DisplayName("a binding the author removed from a type that still stands is swept")
    void aWithdrawnBindingIsSwept(@TempDir Path tmp) {
        write(tmp, "schema.graphqls", """
            type Query { film: Film }
            type Film @table(name: "film") { title: String }
            """);

        ThreadConfinedStore.run(dsl -> {
            read(dsl, tmp);
            assertThat(bound(dsl, "Film")).as("before").isTrue();

            write(tmp, "schema.graphqls", """
                type Query { film: Film }
                type Film { title: String }
                """);
            read(dsl, tmp);

            assertThat(bound(dsl, "Film"))
                .as("the coordinate still stands, so only a sweep can have taken this row")
                .isFalse();
        });
    }

    /**
     * The other half, and the one the clear used to be there for. Removing the type takes its
     * coordinate, and the anchors sweep that coordinate away at the end of their own reading; a
     * binding row still pointing at it would have refused that delete. It does not, because the
     * reference cascades, which is what lets this writer run without a pass emptying it first.
     */
    @Test
    @DisplayName("a type the author removed takes its binding with it, through the cascade")
    void aWithdrawnTypeCascadesToItsBinding(@TempDir Path tmp) {
        write(tmp, "schema.graphqls", """
            type Query { film: Film }
            type Film @table(name: "film") { title: String }
            """);

        ThreadConfinedStore.run(dsl -> {
            read(dsl, tmp);
            assertThat(bound(dsl, "Film")).as("before").isTrue();

            write(tmp, "schema.graphqls", """
                type Query { title: String }
                """);
            read(dsl, tmp);

            assertThat(bound(dsl, "Film"))
                .as("the coordinate went, and the row hanging off it went with it")
                .isFalse();
        });
    }

    /** Whether the corpus binds this type to a table. */
    private boolean bound(DSLContext dsl, String typeName) {
        var a = GRAPHQL_DIRECTIVE_APPLICATION;
        return dsl.fetchExists(dsl.selectOne().from(a)
            .where(a.GRAPH_NAME.eq(graph), a.COORDINATE.eq(typeName),
                a.DIRECTIVE_NAME.eq("table")));
    }

    /** Whether the corpus marks this directive deprecated as a whole. */
    private boolean marked(DSLContext dsl, String directive) {
        var t = GRAPHITRON_DEPRECATED;
        return dsl.fetchExists(dsl.selectOne().from(t)
            .where(t.GRAPH_NAME.eq(graph), t.COORDINATE.eq("@" + directive)));
    }

    /** Whether the corpus marks this argument of this directive deprecated. */
    private boolean markedArgument(DSLContext dsl, String directive, String argument) {
        var t = GRAPHITRON_DEPRECATED;
        return dsl.fetchExists(dsl.selectOne().from(t)
            .where(t.GRAPH_NAME.eq(graph),
                t.COORDINATE.eq("@" + directive + "(" + argument + ":)")));
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
