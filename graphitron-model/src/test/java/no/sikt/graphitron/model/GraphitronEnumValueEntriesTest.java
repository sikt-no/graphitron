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
import no.sikt.graphitron.model.tables.GraphqlAstDirectiveApplicationEntry;
import no.sikt.graphitron.model.vocabulary.EntryKind;
import org.jooq.Condition;
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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INDEX_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.test.ElementOrder.writtenAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The enum-value site's decode: the sorting vocabulary, read where it was written.
 *
 * <p>The claims worth standing up here are the ones this site decides rather than the ones any
 * decode would satisfy. That {@code @field} on an enum value lands beside {@code @field} on an
 * output field, the name written being one fact at every site the directive is admitted at. That
 * {@code @order}'s three surfaces are told apart by which columns are null
 * and whether child rows exist is the relation's whole shape. And that an element's written index
 * survives an element that decodes to nothing is the rule the position key rests on.
 *
 * <p>What the decode writes for a schema the generator accepts is stated by fact documents:
 * {@code @order}'s three surfaces by {@code scenarios/order-by-argument} and
 * {@code scenarios/order-by-index}, and a field list in written order, each element's own fields
 * beside it, by {@code defects/inline-list-choice}. An application the definition does not admit,
 * withheld whole, is {@code defects/entry-legality}'s. The cases here hold what no document does: an
 * enum value carrying {@code @field}, and an {@code @index} naming nothing.
 */
class GraphitronEnumValueEntriesTest {

    /**
     * The case's own graph, named for its method, so a store several cases capture into holds each
     * case's rows apart; every read below filters to it.
     */
    private final String graph;

    GraphitronEnumValueEntriesTest(TestInfo info) {
        graph = "enumValueEntries." + info.getTestMethod().orElseThrow().getName();
    }

    /**
     * The name a {@code @field} writes is one fact wherever it is written, so the enum value's and
     * the output field's are rows of one relation, each joined back to its own parent by position
     * rather than told apart by which relation holds it.
     */
    @Test
    @DisplayName("@field on an enum value and on an output field is one relation")
    void aBindingLandsInOneRelationAtEverySite(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query { films: [Film!] }
            type Film { title: String @field(name: "title") }
            enum FilmOrder { RATING @field(name: "rating_code") }
            """);

        ThreadConfinedStore.run(dsl -> {
            read(dsl, tmp);

            assertThat(dsl.fetch(GRAPHITRON_AST_BINDING_ENTRY,
                    GRAPHITRON_AST_BINDING_ENTRY.GRAPH_NAME.eq(graph)))
                .as("the enum value's binding and the output field's, side by side")
                .extracting(row -> row.get(GRAPHITRON_AST_BINDING_ENTRY.NAME_REF))
                .containsExactlyInAnyOrder("rating_code", "title");
        });
    }

    /**
     * {@code @index} declares one argument, so an application that wrote none would give a row
     * carrying its key and nothing else. That is what the applied-directive row already says, and
     * this case is what holds the two apart: the application is still a fact, and the decode of it
     * is not.
     */
    @Test
    @DisplayName("an @index with no name is applied and decodes to nothing")
    void aSingleArgumentDirectiveWithoutItsArgumentWritesNoRow(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query { films: [Film!] }
            type Film { title: String }
            enum FilmOrder {
              NAMED @index(name: "idx_film_released")
              BARE @index
            }
            """);

        ThreadConfinedStore.run(dsl -> {
            read(dsl, tmp);
            var applied = GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
            var entry = GRAPHQL_AST_ENTRY;
            var value = GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;

            assertThat(appliedAt(dsl, EntryKind.ENUM_VALUE_DIRECTIVE, applied.NAME.eq("index")))
                .as("both applications are facts of the document")
                .hasSize(2);

            assertThat(dsl.select(value.NAME, GRAPHITRON_AST_INDEX_ENTRY.INDEX_REF)
                    .from(GRAPHITRON_AST_INDEX_ENTRY)
                    .join(applied).on(applied.GRAPH_NAME.eq(GRAPHITRON_AST_INDEX_ENTRY.GRAPH_NAME))
                        .and(applied.SOURCE_NAME.eq(GRAPHITRON_AST_INDEX_ENTRY.SOURCE_NAME))
                        .and(applied.SOURCE_LINE.eq(GRAPHITRON_AST_INDEX_ENTRY.SOURCE_LINE))
                        .and(applied.SOURCE_COLUMN.eq(GRAPHITRON_AST_INDEX_ENTRY.SOURCE_COLUMN))
                    .join(entry).on(entryOf(applied, EntryKind.ENUM_VALUE_DIRECTIVE))
                    .join(value).on(value.GRAPH_NAME.eq(entry.GRAPH_NAME))
                        .and(value.SOURCE_NAME.eq(entry.SOURCE_NAME))
                        .and(value.SOURCE_LINE.eq(entry.PARENT_LINE))
                        .and(value.SOURCE_COLUMN.eq(entry.PARENT_COLUMN))
                    .where(GRAPHITRON_AST_INDEX_ENTRY.GRAPH_NAME.eq(graph))
                    .fetch())
                .as("and only the one that named an index decoded to a row")
                .extracting(row -> row.value1(), row -> row.value2())
                .containsExactly(tuple("NAMED", "idx_film_released"));
        });
    }

    /**
     * The names applied at one site: the application entries whose index row gives them that
     * entry kind.
     */
    private List<String> appliedAt(DSLContext dsl, EntryKind site, Condition where) {
        var applied = GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
        return dsl.select(applied.NAME)
            .from(applied)
            .join(GRAPHQL_AST_ENTRY).on(entryOf(applied, site))
            .where(applied.GRAPH_NAME.eq(graph), where)
            .orderBy(applied.NAME)
            .fetch(applied.NAME);
    }

    /**
     * The index row an application is, restricted to one entry kind. It holds the site and the
     * position the application was written on, which the application entry itself does not repeat.
     */
    private static Condition entryOf(GraphqlAstDirectiveApplicationEntry applied, EntryKind site) {
        var entry = GRAPHQL_AST_ENTRY;
        return entry.GRAPH_NAME.eq(applied.GRAPH_NAME)
            .and(entry.SOURCE_NAME.eq(applied.SOURCE_NAME))
            .and(entry.SOURCE_LINE.eq(applied.SOURCE_LINE))
            .and(entry.SOURCE_COLUMN.eq(applied.SOURCE_COLUMN))
            .and(entry.ENTRY_KIND.eq(site));
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
