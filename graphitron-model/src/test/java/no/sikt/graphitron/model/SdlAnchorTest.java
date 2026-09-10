package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlAnchor;
import no.sikt.graphitron.model.capture.document.SdlEntries;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.assertj.core.api.ListAssert;
import org.assertj.core.groups.Tuple;
import org.jooq.DSLContext;
import org.jooq.Table;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_LOCATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS_INTERFACE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The anchor derivation, on what the entries of a corpus add up to.
 *
 * <p>Reads whole documents rather than planting entry rows: the coordinates are generated columns
 * on the entry relations, so a planted row would be asserting the fixture's arithmetic rather than
 * the schema's.
 */
class SdlAnchorTest {

    private static final String GRAPH = "anchors";

    @Test
    @DisplayName("every coordinate the corpus declares gets a row, and the kind is the arm it came from")
    void everyCoordinate(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var file = write(directory, "schema.graphqls", """
                type Film {
                  title: String
                  rated(prefix: String): String
                }
                input FilmFilter {
                  title: String
                }
                enum Rating {
                  G
                  PG
                }
                """);
            read(dsl, LocalDateTime.now(), file);

            declared(dsl)
                .contains(
                    tuple("Film", "NAMED_TYPE"),
                    tuple("FilmFilter", "NAMED_TYPE"),
                    tuple("Rating", "NAMED_TYPE"),
                    tuple("Film.title", "FIELD"),
                    tuple("Film.rated", "FIELD"),
                    tuple("Film.rated(prefix:)", "FIELD_ARGUMENT"),
                    tuple("FilmFilter.title", "INPUT_FIELD"),
                    tuple("Rating.G", "ENUM_VALUE"),
                    tuple("Rating.PG", "ENUM_VALUE"));
        });
    }

    @Test
    @DisplayName("two documents declaring one type are two entries and one element")
    void oneElementPerCoordinate(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var first = write(directory, "first.graphqls", "type Film { title: String }");
            var second = write(directory, "second.graphqls", "type Film { rated: String }");
            read(dsl, LocalDateTime.now(), first, second);

            assertThat(coordinates(dsl)).filteredOn("Film"::equals).hasSize(1);
            declared(dsl).contains(tuple("Film.title", "FIELD"), tuple("Film.rated", "FIELD"));
        });
    }

    @Test
    @DisplayName("a coordinate the corpus stopped declaring is swept, and the rest stand")
    void sweepsWhatWentAway(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var file = write(directory, "schema.graphqls", "type Film { title: String rated: String }");
            read(dsl, LocalDateTime.now(), file);
            declared(dsl).contains(tuple("Film.rated", "FIELD"));

            write(directory, "schema.graphqls", "type Film { title: String }");
            read(dsl, LocalDateTime.now().plusSeconds(1), file);

            declared(dsl)
                .contains(tuple("Film", "NAMED_TYPE"), tuple("Film.title", "FIELD"))
                .doesNotContain(tuple("Film.rated", "FIELD"));
        });
    }

    @Test
    @DisplayName("each key-to-coordinate map holds its own population under its own key")
    void keyToCoordinateMaps(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var file = write(directory, "schema.graphqls", """
                type Film {
                  rated(prefix: String): String
                }
                input FilmFilter {
                  title: String
                }
                enum Rating {
                  G
                }
                """);
            read(dsl, LocalDateTime.now(), file);

            assertThat(dsl.select(GRAPHQL_TYPE_ELEMENT.TYPE_NAME, GRAPHQL_TYPE_ELEMENT.COORDINATE)
                    .from(GRAPHQL_TYPE_ELEMENT).where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(GRAPH))
                    .fetch(row -> tuple(row.value1(), row.value2())))
                .contains(tuple("Film", "Film"), tuple("FilmFilter", "FilmFilter"),
                    tuple("Rating", "Rating"));

            // An object's field and an input object's are one relation, one key shape apiece.
            assertThat(dsl.select(GRAPHQL_FIELD_ELEMENT.TYPE_NAME, GRAPHQL_FIELD_ELEMENT.FIELD_NAME,
                        GRAPHQL_FIELD_ELEMENT.COORDINATE)
                    .from(GRAPHQL_FIELD_ELEMENT).where(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME.eq(GRAPH))
                    .fetch(row -> tuple(row.value1(), row.value2(), row.value3())))
                .contains(tuple("Film", "rated", "Film.rated"),
                    tuple("FilmFilter", "title", "FilmFilter.title"));

            assertThat(dsl.select(GRAPHQL_ARGUMENT_ELEMENT.TYPE_NAME,
                        GRAPHQL_ARGUMENT_ELEMENT.FIELD_NAME, GRAPHQL_ARGUMENT_ELEMENT.ARGUMENT_NAME,
                        GRAPHQL_ARGUMENT_ELEMENT.COORDINATE)
                    .from(GRAPHQL_ARGUMENT_ELEMENT)
                    .where(GRAPHQL_ARGUMENT_ELEMENT.GRAPH_NAME.eq(GRAPH))
                    .fetch(row -> tuple(row.value1(), row.value2(), row.value3(), row.value4())))
                .contains(tuple("Film", "rated", "prefix", "Film.rated(prefix:)"));

            assertThat(dsl.select(GRAPHQL_ENUM_VALUE_ELEMENT.TYPE_NAME,
                        GRAPHQL_ENUM_VALUE_ELEMENT.VALUE_NAME, GRAPHQL_ENUM_VALUE_ELEMENT.COORDINATE)
                    .from(GRAPHQL_ENUM_VALUE_ELEMENT)
                    .where(GRAPHQL_ENUM_VALUE_ELEMENT.GRAPH_NAME.eq(GRAPH))
                    .fetch(row -> tuple(row.value1(), row.value2(), row.value3())))
                .contains(tuple("Rating", "G", "Rating.G"));
        });
    }

    @Test
    @DisplayName("a type that went away takes its fields and arguments with it, parents last")
    void sweepsTheWholeChain(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var file = write(directory, "schema.graphqls", """
                type Film {
                  rated(prefix: String): String
                }
                type Actor {
                  name: String
                }
                """);
            read(dsl, LocalDateTime.now(), file);
            assertThat(coordinates(dsl)).contains("Film", "Film.rated", "Film.rated(prefix:)");

            write(directory, "schema.graphqls", "type Actor { name: String }");
            read(dsl, LocalDateTime.now().plusSeconds(1), file);

            // The whole chain goes, and the sweep order is what lets it: an argument row referencing
            // a field row referencing a type row, deleted innermost first rather than refused.
            assertThat(coordinates(dsl))
                .doesNotContain("Film", "Film.rated", "Film.rated(prefix:)")
                .contains("Actor", "Actor.name");
            // Named rather than counted: the bundled directive vocabulary is read alongside and
            // declares types of its own, so a count would be asserting something about that document.
            assertThat(dsl.select(GRAPHQL_TYPE_ELEMENT.TYPE_NAME).from(GRAPHQL_TYPE_ELEMENT)
                    .where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(GRAPH))
                    .fetch(GRAPHQL_TYPE_ELEMENT.TYPE_NAME))
                .contains("Actor").doesNotContain("Film");
            assertThat(dsl.select(GRAPHQL_ARGUMENT_ELEMENT.COORDINATE).from(GRAPHQL_ARGUMENT_ELEMENT)
                    .where(GRAPHQL_ARGUMENT_ELEMENT.GRAPH_NAME.eq(GRAPH))
                    .fetch(GRAPHQL_ARGUMENT_ELEMENT.COORDINATE))
                .doesNotContain("Film.rated(prefix:)");
        });
    }

    @Test
    @DisplayName("the content anchors carry the payload, and ordinals count from the base declaration")
    void contentAnchors(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var file = write(directory, "schema.graphqls", """
                "the film"
                type Film {
                  title: String!
                  rated(prefix: String = "x"): [String]
                }
                input FilmFilter {
                  title: String = "any"
                }
                enum Rating {
                  G
                  PG
                }
                """);
            read(dsl, LocalDateTime.now(), file);

            assertThat(dsl.select(GRAPHQL_TYPE.TYPE_NAME, GRAPHQL_TYPE.KIND, GRAPHQL_TYPE.DESCRIPTION)
                    .from(GRAPHQL_TYPE).where(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPH))
                    .fetch(row -> tuple(row.value1(), row.value2(), row.value3())))
                .contains(tuple("Film", "OBJECT", "the film"),
                    tuple("FilmFilter", "INPUT_OBJECT", null),
                    tuple("Rating", "ENUM", null));

            // The type expression is decomposed as the entry decomposed it, and the ordinal counts
            // within the type rather than within the file.
            assertThat(dsl.select(GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.ORDINAL,
                        GRAPHQL_FIELD.TYPE_SDL, GRAPHQL_FIELD.NON_NULL, GRAPHQL_FIELD.IS_LIST)
                    .from(GRAPHQL_FIELD)
                    .where(GRAPHQL_FIELD.GRAPH_NAME.eq(GRAPH)).and(GRAPHQL_FIELD.TYPE_NAME.eq("Film"))
                    .fetch(row -> tuple(row.value1(), row.value2(), row.value3(), row.value4(),
                        row.value5())))
                .containsExactlyInAnyOrder(
                    tuple("title", 1, "String!", true, false),
                    tuple("rated", 2, "[String]", false, true));

            // An input field lands in the same relation and is the one kind that carries a default.
            assertThat(dsl.select(GRAPHQL_FIELD.DEFAULT_VALUE_SDL).from(GRAPHQL_FIELD)
                    .where(GRAPHQL_FIELD.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_FIELD.TYPE_NAME.eq("FilmFilter"))
                    .fetch(GRAPHQL_FIELD.DEFAULT_VALUE_SDL))
                .containsExactly("\"any\"");

            assertThat(dsl.select(GRAPHQL_ARGUMENT.ARGUMENT_NAME, GRAPHQL_ARGUMENT.ORDINAL,
                        GRAPHQL_ARGUMENT.DEFAULT_VALUE_SDL)
                    .from(GRAPHQL_ARGUMENT).where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_ARGUMENT.TYPE_NAME.eq("Film"))
                    .fetch(row -> tuple(row.value1(), row.value2(), row.value3())))
                .containsExactly(tuple("prefix", 1, "\"x\""));

            assertThat(dsl.select(GRAPHQL_ENUM_VALUE.VALUE_NAME, GRAPHQL_ENUM_VALUE.ORDINAL)
                    .from(GRAPHQL_ENUM_VALUE).where(GRAPHQL_ENUM_VALUE.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_ENUM_VALUE.TYPE_NAME.eq("Rating"))
                    .fetch(row -> tuple(row.value1(), row.value2())))
                .containsExactlyInAnyOrder(tuple("G", 1), tuple("PG", 2));
        });
    }

    @Test
    @DisplayName("an extension merges after the base declaration whatever order the files are read in")
    void extensionsMergeAfterTheBase(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            // The extension is the older file, so a rule that ordered by mtime alone would put its
            // fields first. Base before extension is what decides it.
            var extension = write(directory, "a-extension.graphqls", "extend type Film { rated: String }");
            var base = write(directory, "b-base.graphqls", """
                "the film"
                type Film { title: String }
                """);
            touch(extension, "2020-01-01T00:00:00");
            touch(base, "2030-01-01T00:00:00");
            read(dsl, LocalDateTime.now(), extension, base);

            assertThat(dsl.select(GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.ORDINAL)
                    .from(GRAPHQL_FIELD).where(GRAPHQL_FIELD.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_FIELD.TYPE_NAME.eq("Film"))
                    .fetch(row -> tuple(row.value1(), row.value2())))
                .containsExactlyInAnyOrder(tuple("title", 1), tuple("rated", 2));

            // The kind and the description are the base declaration's, an extension carrying neither.
            assertThat(dsl.select(GRAPHQL_TYPE.KIND, GRAPHQL_TYPE.DESCRIPTION).from(GRAPHQL_TYPE)
                    .where(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPH)).and(GRAPHQL_TYPE.TYPE_NAME.eq("Film"))
                    .fetch(row -> tuple(row.value1(), row.value2())))
                .containsExactly(tuple("OBJECT", "the film"));

            // Both sites are kept, ranked, which is what the ordinals above were computed from.
            assertThat(dsl.select(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL,
                        GRAPHQL_TYPE_DECLARATION.IS_EXTENSION)
                    .from(GRAPHQL_TYPE_DECLARATION)
                    .where(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq("Film"))
                    .fetch(row -> tuple(row.value1(), row.value2())))
                .containsExactlyInAnyOrder(tuple(1, false), tuple(2, true));
        });
    }

    @Test
    @DisplayName("a union names its members and a type names its interfaces, from opposite ends")
    void polyMembers(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var file = write(directory, "schema.graphqls", """
                interface Node { id: ID! }
                interface Named { name: String }
                type Film implements Node & Named { id: ID! name: String }
                union Searchable = Film | Actor
                type Actor { id: ID! }
                """);
            read(dsl, LocalDateTime.now(), file);

            // The union's container is the declaring type; the interface's container is the
            // interface and the member is whoever implements it.
            assertThat(dsl.select(GRAPHQL_POLY_MEMBER.CONTAINER_KIND,
                        GRAPHQL_POLY_MEMBER.CONTAINER_NAME, GRAPHQL_POLY_MEMBER.MEMBER_TYPE_NAME,
                        GRAPHQL_POLY_MEMBER.DECLARED_ON, GRAPHQL_POLY_MEMBER.POSITION)
                    .from(GRAPHQL_POLY_MEMBER).where(GRAPHQL_POLY_MEMBER.GRAPH_NAME.eq(GRAPH))
                    .fetch(row -> tuple(row.value1(), row.value2(), row.value3(), row.value4(),
                        row.value5())))
                .contains(
                    // A union numbers from zero and an interface from one, which is inherited and
                    // reproduced rather than corrected.
                    tuple("UNION", "Searchable", "Film", "Searchable", 0),
                    tuple("UNION", "Searchable", "Actor", "Searchable", 1),
                    tuple("INTERFACE", "Node", "Film", "Film", 1),
                    tuple("INTERFACE", "Named", "Film", "Film", 1));
        });
    }

    @Test
    @DisplayName("the directive vocabulary carries its locations and its arguments")
    void directives(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var file = write(directory, "schema.graphqls", """
                "binds a table"
                directive @table(name: String, schema: String = "public") repeatable
                    on OBJECT | INTERFACE
                type Film { title: String }
                """);
            read(dsl, LocalDateTime.now(), file);

            assertThat(dsl.select(GRAPHQL_DIRECTIVE.REPEATABLE, GRAPHQL_DIRECTIVE.DESCRIPTION)
                    .from(GRAPHQL_DIRECTIVE).where(GRAPHQL_DIRECTIVE.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME.eq("table"))
                    .fetch(row -> tuple(row.value1(), row.value2())))
                .containsExactly(tuple(true, "binds a table"));

            assertThat(dsl.select(GRAPHQL_DIRECTIVE_LOCATION.LOCATION)
                    .from(GRAPHQL_DIRECTIVE_LOCATION)
                    .where(GRAPHQL_DIRECTIVE_LOCATION.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_DIRECTIVE_LOCATION.DIRECTIVE_NAME.eq("table"))
                    .fetch(GRAPHQL_DIRECTIVE_LOCATION.LOCATION))
                .containsExactlyInAnyOrder("OBJECT", "INTERFACE");

            assertThat(dsl.select(GRAPHQL_DIRECTIVE_ARGUMENT.ARGUMENT_NAME,
                        GRAPHQL_DIRECTIVE_ARGUMENT.ORDINAL,
                        GRAPHQL_DIRECTIVE_ARGUMENT.DEFAULT_VALUE_SDL)
                    .from(GRAPHQL_DIRECTIVE_ARGUMENT)
                    .where(GRAPHQL_DIRECTIVE_ARGUMENT.GRAPH_NAME.eq(GRAPH))
                    .and(GRAPHQL_DIRECTIVE_ARGUMENT.DIRECTIVE_NAME.eq("table"))
                    .fetch(row -> tuple(row.value1(), row.value2(), row.value3())))
                .containsExactlyInAnyOrder(tuple("name", 1, null), tuple("schema", 2, "\"public\""));
        });
    }

    @Test
    @DisplayName("a spelled root binding wins, and the name convention fills what none spells")
    void rootOperations(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var file = write(directory, "schema.graphqls", """
                schema { query: Root }
                type Root { title: String }
                type Query { ignored: String }
                type Mutation { save: String }
                """);
            read(dsl, LocalDateTime.now(), file);

            // QUERY is spelled and points at Root, not at the type named Query. MUTATION is not
            // spelled, so the convention supplies it and leaves the position columns null.
            assertThat(dsl.select(GRAPHQL_ROOT_OPERATION.OPERATION, GRAPHQL_ROOT_OPERATION.TYPE_NAME,
                        GRAPHQL_ROOT_OPERATION.SOURCE_NAME)
                    .from(GRAPHQL_ROOT_OPERATION)
                    .where(GRAPHQL_ROOT_OPERATION.GRAPH_NAME.eq(GRAPH))
                    .fetch(row -> tuple(row.value1(), row.value2(), row.value3() == null)))
                .containsExactlyInAnyOrder(
                    tuple("QUERY", "Root", false),
                    tuple("MUTATION", "Mutation", true));
        });
    }

    @Test
    @DisplayName("reading the same corpus twice leaves the same rows, restamped")
    void capturingTwiceRestampsRatherThanDuplicating(@TempDir Path directory) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var file = write(directory, "schema.graphqls", """
                schema { query: Root }
                directive @table(name: String) on OBJECT
                type Root { film: Film }
                type Film implements Node @table(name: "film") { id: ID! title(p: String): String }
                interface Node { id: ID! }
                union Searchable = Film
                input FilmFilter { title: String }
                enum Rating { G }
                """);
            var first = LocalDateTime.now();
            read(dsl, first, file);
            var afterFirst = census(dsl);

            // A second reading of an unchanged corpus, which is where a sweep predicate that is
            // wrong on the second pass shows it: rows deleted, duplicated, or left carrying the
            // older instant. The first pass cannot fail that way, having nothing to sweep.
            var second = first.plusSeconds(1);
            read(dsl, second, file);

            assertThat(census(dsl))
                .as("the same corpus derives the same rows however often it is read")
                .isEqualTo(afterFirst);
            assertThat(stamps(dsl, second))
                .as("and every one of them belongs to the reading that just ran")
                .isEqualTo(afterFirst.values().stream().mapToInt(Integer::intValue).sum());
        });
    }

    /** How many rows each derived anchor holds, which is what a second reading must not change. */
    private static Map<String, Integer> census(DSLContext dsl) {
        var counts = new LinkedHashMap<String, Integer>();
        for (Table<?> table : DERIVED) {
            counts.put(table.getName(), dsl.fetchCount(table,
                table.field("GRAPH_NAME", String.class).eq(GRAPH)));
        }
        return counts;
    }

    /** Rows across the anchors carrying one reading's instant. */
    private static int stamps(DSLContext dsl, LocalDateTime touchedAt) {
        int total = 0;
        for (Table<?> table : DERIVED) {
            total += dsl.fetchCount(table, table.field("GRAPH_NAME", String.class).eq(GRAPH)
                .and(table.field("TOUCHED_AT", LocalDateTime.class).eq(touchedAt)));
        }
        return total;
    }

    /**
     * The anchors the derivation writes, listed rather than found by prefix: this case is about
     * whether a second reading disturbs them, and a relation missing from the list is one the case
     * would pass without having looked at.
     */
    private static final List<Table<?>> DERIVED = List.of(
        GRAPHQL_ELEMENT, GRAPHQL_TYPE_ELEMENT, GRAPHQL_FIELD_ELEMENT, GRAPHQL_ENUM_VALUE_ELEMENT,
        GRAPHQL_ARGUMENT_ELEMENT, GRAPHQL_TYPE_DECLARATION, GRAPHQL_TYPE, GRAPHQL_FIELD,
        GRAPHQL_ENUM_VALUE, GRAPHQL_ARGUMENT, GRAPHQL_UNION_MEMBER, GRAPHQL_IMPLEMENTS_INTERFACE,
        GRAPHQL_DIRECTIVE, GRAPHQL_DIRECTIVE_LOCATION, GRAPHQL_DIRECTIVE_ARGUMENT,
        GRAPHQL_ROOT_OPERATION);

    /** The graph's elements as coordinate and kind, which is the whole of what this anchor holds. */
    private static ListAssert<Tuple> declared(DSLContext dsl) {
        return assertThat(dsl
            .select(GRAPHQL_ELEMENT.COORDINATE, GRAPHQL_ELEMENT.ELEMENT_KIND)
            .from(GRAPHQL_ELEMENT)
            .where(GRAPHQL_ELEMENT.GRAPH_NAME.eq(GRAPH))
            .fetch(row -> tuple(row.value1(), row.value2())));
    }

    /** The coordinates alone, for a count that does not care what kind sits at one. */
    private static List<String> coordinates(DSLContext dsl) {
        return dsl.select(GRAPHQL_ELEMENT.COORDINATE)
            .from(GRAPHQL_ELEMENT)
            .where(GRAPHQL_ELEMENT.GRAPH_NAME.eq(GRAPH))
            .fetch(GRAPHQL_ELEMENT.COORDINATE);
    }

    /**
     * Reads every file as its own document, then derives the anchors once, which is the cadence
     * {@code SdlCapture} runs them at.
     */
    private static void read(DSLContext dsl, LocalDateTime touchedAt, Path... files) {
        seedSource(dsl, SchemaLoader.DIRECTIVES_SOURCE_NAME, "SCHEMA_FILE");
        Arrays.stream(files).forEach(file -> seedSource(dsl, file.toString(), "SCHEMA_FILE"));
        SchemaLoader.parsePerSource(Arrays.stream(files).map(SchemaSource::file).toList())
            .perSource()
            .forEach(document ->
                SdlEntries.write(dsl, GRAPH, document.sourceName(), document.registry(), touchedAt));
        SdlAnchor.write(dsl, GRAPH, touchedAt);
    }

    /** Sets a file's modification time, which is what the merge order sorts sources by. */
    private static void touch(Path file, String isoInstant) {
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(
                java.time.LocalDateTime.parse(isoInstant).atZone(java.time.ZoneId.systemDefault())
                    .toInstant()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path write(Path directory, String name, String sdl) {
        try {
            return Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
