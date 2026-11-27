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
import no.sikt.graphitron.model.vocabulary.EntryKind;
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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CODE_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entry relations hold what the directive definition admits, and hold it only then.
 *
 * <p>The refusals are stated by fact documents: {@code defects/entry-legality} writes an
 * application at the wrong site, an element of the wrong shape, an undeclared argument, a literal of
 * the wrong type and a {@code @field} with no name, assembly refusing each and no decode holding
 * any, and {@code defects/error-handler-without-kind} an element missing its required field. What
 * stays here is the other direction, which no document writes: spellings a rule written slightly too
 * tightly would refuse, captured whole so that assembly accepting them is part of the claim.
 */
class EntryLegalityTest {

    /**
     * {@code FieldSort.name} is optional, because an element may write a {@code method:} instead,
     * so an element naming no column is a {@code FieldSort} and is transcribed with a {@code NULL}
     * name. The method is refused, {@code SORT_KEY_METHOD}, and transcribed all the same, its
     * reference a written code reference like any other, so the refusal has something to point at.
     */
    @Test
    @DisplayName("an order element naming a method instead of a column is transcribed with no name")
    void anOrderElementNamingAMethodIsTranscribed(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "method-entry", """
                type Query { films(order: FilmOrder): [Film!] }
                type Film { title: String }
                enum FilmOrder {
                    LATEST @order(fields: [{method: {className: "com.example.FilmKeys", method: "latest"}}])
                }
                """));
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                .from(GRAPHQL_SCHEMA_PROBLEM)
                .where(GRAPHQL_SCHEMA_PROBLEM.GRAPH_NAME.eq("method-entry")).fetch())
                .as("the corpus assembles")
                .isEmpty();
            var e = GRAPHITRON_AST_ORDER_FIELD_ENTRY;
            assertThat(dsl.select(e.NAME_REF).from(e).where(e.GRAPH_NAME.eq("method-entry")).fetch(e.NAME_REF))
                .as("the element is transcribed, with no name")
                .containsExactly((String) null);
            var c = GRAPHITRON_AST_CODE_REFERENCE_ENTRY;
            assertThat(dsl.select(c.CLASS_NAME, c.METHOD).from(c).where(c.GRAPH_NAME.eq("method-entry"))
                .fetch(r -> r.value1() + "#" + r.value2()))
                .as("and its method is a code reference")
                .containsExactly("com.example.FilmKeys#latest");
        });
    }

    /**
     * The other direction, and the one worth most. Everything here is legal, including the parts a
     * predicate written slightly too tightly would refuse: an optional argument omitted, a
     * one-element list written without brackets, an enum token, and a defaulted argument left out.
     * A rule stricter than assembly deletes these rows and reports nothing, readers of these relations
     * joining to them inwardly.
     */
    @Test
    @DisplayName("every legal spelling is transcribed, including the ones a tight rule would refuse")
    void legalSpellingsAreAllTranscribed(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "legal", """
                type Query { films(order: FilmOrder): [Film!] }
                type Film @table(name: "film") { title: String }
                enum FilmOrder {
                  BY_LIST @order(fields: [{name: "title", direction: DESC}])
                  BY_BARE_OBJECT @order(fields: {name: "title"})
                  BY_INDEX @order(index: "film_title_idx")
                  BY_PRIMARY_KEY @order(primaryKey: true)
                }
                """));
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                .from(GRAPHQL_SCHEMA_PROBLEM)
                .where(GRAPHQL_SCHEMA_PROBLEM.GRAPH_NAME.eq("legal")).fetch())
                .as("the corpus assembles, so anything missing below is this rule's doing")
                .isEmpty();
            assertThat(dsl.fetchCount(GRAPHITRON_AST_ORDER_ENTRY,
                GRAPHITRON_AST_ORDER_ENTRY.GRAPH_NAME.eq("legal")))
                .as("all four applications are admitted, the bare object literal among them, the "
                    + "specification coercing a lone value into a one-element list")
                .isEqualTo(4);
            assertThat(dsl.fetchCount(GRAPHITRON_AST_ORDER_FIELD_ENTRY,
                GRAPHITRON_AST_ORDER_FIELD_ENTRY.GRAPH_NAME.eq("legal")))
                .as("and both spellings of the list contribute their element")
                .isEqualTo(2);
        });
    }

    private static Path corpus(Path parent, String name, String sdl) {
        Path directory = parent.resolve(name);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("corpus.graphqls"), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return directory;
    }

    /** The whole face, because the problem rows are half of every assertion here. */
    private static void read(DSLContext dsl, Path directory) {
        var graph = new GraphIdentity(directory.getFileName().toString(), directory);
        var config = SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
        var readAt = LocalDateTime.now();
        ModelCapture.writeGraph(dsl, graph, readAt);
        var reading = GraphQLSourceCapture.capture(dsl, graph, config, readAt);
        var documents = reading.documents();
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
        GraphQLAssemblyCapture.capture(dsl, graph, reading, readAt);
    }
}
