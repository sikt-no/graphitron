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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ENUM_VALUE_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INDEX_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static no.sikt.graphitron.model.test.ElementOrder.writtenAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The enum-value site's decode: the sorting vocabulary, read where it was written.
 *
 * <p>The claims worth standing up here are the ones this site decides rather than the ones any
 * decode would satisfy. That {@code @field} on an enum value is a different population from
 * {@code @field} on an output field is what one writer per site buys, the directive being admitted
 * at four of them. That {@code @order}'s three surfaces are told apart by which columns are null
 * and whether child rows exist is the relation's whole shape. And that an element's written index
 * survives an element that decodes to nothing is the rule the position key rests on.
 */
class GraphitronEnumValueEntriesTest {

    private static final String GRAPH = "enum-value";

    /**
     * The site is the key, so the same directive name at two sites is two relations. This is the
     * claim the writer exists for: the incumbent routed both down one path and told them apart by
     * the coordinate columns it wrote, where here they are simply different rows in different
     * relations, joined back to their own parent.
     */
    @Test
    @DisplayName("@field on an enum value is not the field site's binding")
    void theSiteDecidesWhichRelationABindingLandsIn(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query { films: [Film!] }
            type Film { title: String @field(name: "title") }
            enum FilmOrder { RATING @field(name: "rating_code") }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.fetch(GRAPHITRON_AST_ENUM_VALUE_BINDING_ENTRY))
                .as("the enum value's binding, and only it, is this site's")
                .extracting(row -> row.get(GRAPHITRON_AST_ENUM_VALUE_BINDING_ENTRY.NAME_REF))
                .containsExactly("rating_code");

            assertThat(dsl.fetch(GRAPHITRON_AST_FIELD_BINDING_ENTRY))
                .as("and the output field's stayed where the field site writes it")
                .extracting(row -> row.get(GRAPHITRON_AST_FIELD_BINDING_ENTRY.NAME_REF))
                .containsExactly("title");
        });
    }

    /**
     * The directive admits exactly one of the three, and the relation states which was taken by
     * what is null rather than by a discriminator. The field-list arm is the one that needs the
     * parent row at all: both its own columns are null and its answer is the rows beside it.
     */
    @Test
    @DisplayName("@order's three surfaces are told apart by what is null and what hangs off")
    void theThreeSortingSurfacesAreDistinguishable(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query { films: [Film!] }
            type Film { title: String }
            enum FilmOrder {
              BY_INDEX @order(index: "idx_film_title")
              BY_KEY @order(primaryKey: true)
              BY_FIELDS @order(fields: [{name: "title"}])
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_AST_ORDER_ENTRY;

            assertThat(dsl.select(t.INDEX_REF, t.PRIMARY_KEY).from(t)
                    .orderBy(t.SOURCE_LINE).fetch())
                .as("one row per application, each saying which surface by what it left null")
                .extracting(row -> row.value1(), row -> row.value2())
                .containsExactly(
                    tuple("idx_film_title", null),
                    tuple(null, true),
                    tuple(null, null));

            assertThat(dsl.fetchCount(GRAPHITRON_AST_ORDER_FIELD_ENTRY))
                .as("and only the field-list arm has children")
                .isEqualTo(1);
        });
    }

    /**
     * The position an element was written at is the sort order the author asked for, so it is a key
     * column and not a derived one, and each element's own fields are columns beside it.
     */
    @Test
    @DisplayName("the field list becomes rows carrying the order they were written in")
    void theFieldListKeepsItsWrittenOrder(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query { films: [Film!] }
            type Film { title: String }
            enum FilmOrder {
              BY_TITLE @order(fields: [
                {name: "title", collate: "xdanish_ai"},
                {name: "release_year"}
              ])
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_AST_ORDER_FIELD_ENTRY;

            assertThat(dsl.select(writtenAt(t), t.NAME_REF, t.COLLATE).from(t)
                    .orderBy(writtenAt(t)).fetch())
                .as("both elements, at the indices the author wrote them at")
                .extracting(row -> row.value1(), row -> row.value2(), row -> row.value3())
                .containsExactly(
                    tuple(0, "title", "xdanish_ai"),
                    tuple(1, "release_year", null));
        });
    }

    /**
     * An element the definition does not admit withdraws the whole application, so no position of
     * the list is written at all.
     *
     * <p>This case used to pin the opposite, that such an element spent its index and left the ones
     * after it where the author would count them. That was the reading before the entry stratum
     * judged an application against its definition, when a list could be part legal and part not
     * and the numbering had to say which. It cannot be now: {@code FieldSort} declares
     * {@code name} as {@code String!}, so a list carrying a bare string is not a list of
     * {@code FieldSort} and the application is not one {@code @order} admits. Its twin at the field
     * site went the same way in the commit that made it unreachable, and the corpus here is that
     * corpus: assembly refuses it, so nothing that reaches a generator can carry the shape the old
     * case was about.
     */
    @Test
    @DisplayName("an element the definition does not admit withdraws the whole application")
    void anElementTheDefinitionDoesNotAdmitWithdrawsTheApplication(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query { films: [Film!] }
            type Film { title: String }
            enum FilmOrder {
              BY_TITLE @order(fields: ["title", {name: "release_year"}])
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_AST_ORDER_FIELD_ENTRY;

            assertThat(dsl.select(writtenAt(t), t.NAME_REF).from(t).fetch())
                .as("the legal element is withheld with the illegal one, the application being the "
                    + "unit the definition admits or does not")
                .isEmpty();
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

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var applied = GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY;
            var value = GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;

            assertThat(dsl.fetchCount(applied, applied.NAME.eq("index")))
                .as("both applications are facts of the document")
                .isEqualTo(2);

            assertThat(dsl.select(value.NAME, GRAPHITRON_AST_INDEX_ENTRY.INDEX_REF)
                    .from(GRAPHITRON_AST_INDEX_ENTRY)
                    .join(applied).on(applied.GRAPH_NAME.eq(GRAPHITRON_AST_INDEX_ENTRY.GRAPH_NAME))
                        .and(applied.SOURCE_NAME.eq(GRAPHITRON_AST_INDEX_ENTRY.SOURCE_NAME))
                        .and(applied.SOURCE_LINE.eq(GRAPHITRON_AST_INDEX_ENTRY.SOURCE_LINE))
                        .and(applied.SOURCE_COLUMN.eq(GRAPHITRON_AST_INDEX_ENTRY.SOURCE_COLUMN))
                    .join(value).on(value.GRAPH_NAME.eq(applied.GRAPH_NAME))
                        .and(value.SOURCE_NAME.eq(applied.SOURCE_NAME))
                        .and(value.SOURCE_LINE.eq(applied.PARENT_LINE))
                        .and(value.SOURCE_COLUMN.eq(applied.PARENT_COLUMN))
                    .fetch())
                .as("and only the one that named an index decoded to a row")
                .extracting(row -> row.value1(), row -> row.value2())
                .containsExactly(tuple("NAMED", "idx_film_released"));
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
