package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlAnchor;
import no.sikt.graphitron.model.capture.document.SdlEntries;
import no.sikt.graphitron.model.capture.sdl.SdlFactCapture;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.sink.FactSink;
import no.sikt.graphitron.model.sources.ClasspathSources;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_LOCATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DUPLICATE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS_INTERFACE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_MEMBER;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two writers, one set of relations, and the question of whether the older one is still saying
 * anything the newer one does not.
 *
 * <p>The {@code graphql_} anchors have two producers. {@link SdlFactCapture} walks a merged registry
 * with graphql-java accessors in hand and writes them directly; {@link SdlAnchor} derives the same
 * relations from the entry stratum with SQL. Both run on a mojo build, the derivation first and the
 * walk second, so the rows a reader sees are the walk's and the derivation's are overwritten
 * unexamined. That is the arrangement this case exists to end: nothing chose it, and while it holds,
 * every column added to an anchor has to be taught to two writers in two languages, which is what
 * stopped {@code list_depth} reaching the reader it was cut for.
 *
 * <p>So the corpus is captured twice into two stores, once by each producer, and the relations are
 * compared row for row. What the comparison excludes is named rather than waved at: the instant,
 * because two readings are two instants and that is the point of the column, and the generated
 * columns, which are a function of the row beside them and would report one disagreement twice.
 *
 * <p>A disagreement here is not automatically the derivation being wrong. It is a fact one producer
 * holds and the other does not, and which of them is right is a question about the fact. What the
 * case gives is the list, exactly, instead of an argument about whether the list is short.
 */
class SdlWalkIsRedundantTest {

    private static final String GRAPH = "test-graph";

    /**
     * The relations both producers write. Twenty-six of the walk's twenty-seven; the twenty-seventh
     * is {@link #OVERFLOW} below.
     */
    private static final List<Table<?>> SHARED = List.of(
        GRAPHQL_TYPE, GRAPHQL_TYPE_DECLARATION, GRAPHQL_FIELD, GRAPHQL_ARGUMENT, GRAPHQL_ENUM_VALUE,
        GRAPHQL_ELEMENT, GRAPHQL_TYPE_ELEMENT, GRAPHQL_FIELD_ELEMENT, GRAPHQL_ARGUMENT_ELEMENT,
        GRAPHQL_ENUM_VALUE_ELEMENT,
        GRAPHQL_IMPLEMENTS_INTERFACE, GRAPHQL_UNION_MEMBER, GRAPHQL_ROOT_OPERATION,
        GRAPHQL_DIRECTIVE, GRAPHQL_DIRECTIVE_LOCATION, GRAPHQL_DIRECTIVE_ARGUMENT,
        GRAPHQL_SCHEMA_DIRECTIVE, GRAPHQL_SCHEMA_DIRECTIVE_ARG,
        GRAPHQL_TYPE_DIRECTIVE, GRAPHQL_TYPE_DIRECTIVE_ARG,
        GRAPHQL_FIELD_DIRECTIVE, GRAPHQL_FIELD_DIRECTIVE_ARG,
        GRAPHQL_ARGUMENT_DIRECTIVE, GRAPHQL_ARGUMENT_DIRECTIVE_ARG,
        GRAPHQL_ENUM_VALUE_DIRECTIVE, GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG);

    /**
     * The one relation only the walk writes, and the reason the derivation owes it nothing. It is
     * the overflow of a coordinate-keyed grain: the anchors admit one row per coordinate and the
     * losing occurrence of a duplicate has nowhere to go, so it lands here rendered as text. The
     * entry stratum is keyed by source position, where a field declared twice is simply two rows,
     * so the shape that needs an overflow is the shape the entries replaced. Nothing in the tree
     * reads it.
     */
    private static final Table<?> OVERFLOW = GRAPHQL_DUPLICATE_DECLARATION;

    /** Two readings are two instants, which is what the sweep tells them apart by. */
    private static final Set<String> EXCLUDED_COLUMNS = Set.of("TOUCHED_AT");

    /**
     * A corpus wide enough that agreement means something: every declaration form, both directive
     * sites that carry arguments, an interface, a union, an enum, an extension, and a schema block
     * with a non-default root operation name.
     */
    private static final String SDL = """
        directive @shape(at: String, size: Int = 3) repeatable on OBJECT | FIELD_DEFINITION
        directive @tag(name: String!) on ARGUMENT_DEFINITION | ENUM_VALUE | SCHEMA

        schema @tag(name: "root") { query: Root, mutation: Changes }

        "a described root"
        type Root {
          "a described field"
          films(first: Int @tag(name: "page"), after: String): [Film!]! @shape(at: "here")
          media: Media
          any: Anything
        }

        type Changes { touch(what: Touch): Boolean }

        input Touch { id: ID!, note: String = "none", tags: [String!] }

        interface Media { title: String }

        type Film implements Media @shape(at: "film") @shape(at: "twice") {
          title: String
          rating: Rating
        }

        type Book implements Media { title: String, isbn: String }

        union Anything = Film | Book

        enum Rating {
          "a described value"
          G @tag(name: "general")
          PG
        }

        scalar Date

        extend type Film { released: Date }

        # A second argument-bearing field on a type that already had one, supplied by an extension.
        # Without it the two producers cannot disagree about graphql_argument.ordinal: with one such
        # field per type, numbering per type and numbering per field give the same values, and the
        # case passed while the counters differed in scope.
        extend type Root { search(term: String!, limit: Int): [Media!] }
        """;

    @Test
    @DisplayName("the derivation writes what the walk writes, relation by relation")
    void theDerivationWritesWhatTheWalkWrites(@TempDir Path tmp) {
        Path file = write(tmp, "corpus.graphqls", SDL);

        Map<String, Map<String, Map<String, Object>>> byWalk = new TreeMap<>();
        Map<String, Map<String, Map<String, Object>>> byDerivation = new TreeMap<>();
        withSeededStore(GRAPH, dsl -> {
            runWalk(dsl, file);
            SHARED.forEach(table -> byWalk.put(table.getName(), keyedRows(dsl, table)));
        });
        withSeededStore(GRAPH, dsl -> {
            runDerivation(dsl, file);
            SHARED.forEach(table -> byDerivation.put(table.getName(), keyedRows(dsl, table)));
        });

        assertThat(byWalk.values().stream().mapToInt(Map::size).sum())
            .as("the walk wrote nothing, so this case would agree vacuously")
            .isGreaterThan(100);

        var disagreements = new TreeMap<String, Object>();
        for (String relation : byWalk.keySet()) {
            Map<String, Map<String, Object>> walk = byWalk.get(relation);
            Map<String, Map<String, Object>> derived = byDerivation.get(relation);

            var onlyWalk = new TreeSet<>(walk.keySet());
            onlyWalk.removeAll(derived.keySet());
            var onlyDerived = new TreeSet<>(derived.keySet());
            onlyDerived.removeAll(walk.keySet());
            var differingColumns = new TreeSet<String>();
            walk.forEach((key, walkRow) -> {
                Map<String, Object> derivedRow = derived.get(key);
                if (derivedRow != null) {
                    walkRow.forEach((column, value) -> {
                        if (!java.util.Objects.equals(value, derivedRow.get(column))) {
                            differingColumns.add(column);
                        }
                    });
                }
            });
            if (!onlyWalk.isEmpty() || !onlyDerived.isEmpty() || !differingColumns.isEmpty()) {
                var finding = new java.util.LinkedHashMap<String, Object>();
                if (!onlyWalk.isEmpty()) finding.put("rows only the walk has", onlyWalk);
                if (!onlyDerived.isEmpty()) finding.put("rows only the derivation has", onlyDerived);
                if (!differingColumns.isEmpty()) finding.put("columns that differ", differingColumns);
                disagreements.put(relation, finding);
            }
        }

        assertThat(disagreements)
            .as("relations the two producers of the graphql_ anchors disagree about. A row only one "
                + "of them has is a fact the other loses; a column that differs is a fact they "
                + "read differently, and which reading is right is a question about the fact")
            .isEmpty();
    }

    /**
     * The overflow's own claim, kept beside the agreement rather than folded into it: the walk fills
     * it on a corpus with a duplicate, the derivation leaves it empty, and the same duplicate stands
     * in the entry stratum as two ordinary rows at two positions. That is what says the relation is
     * a consequence of the coordinate grain rather than a fact the entries lose.
     */
    @Test
    @DisplayName("the overflow relation holds what position-keyed rows hold anyway")
    void theOverflowHoldsWhatPositionKeyedRowsHoldAnyway(@TempDir Path tmp) {
        Path file = write(tmp, "twice.graphqls", """
            type Query { title: String, title: Int }
            """);

        withSeededStore(GRAPH, dsl -> {
            runWalk(dsl, file);
            assertThat(dsl.fetchCount(OVERFLOW))
                .as("the walk keeps the losing occurrence here, the coordinate grain having no "
                    + "room for it")
                .isEqualTo(1);
        });
        withSeededStore(GRAPH, dsl -> {
            runDerivation(dsl, file);
            assertThat(dsl.fetchCount(OVERFLOW))
                .as("the derivation writes no overflow, and this is the whole of what the walk "
                    + "still says that it does not")
                .isZero();
            assertThat(dsl.fetchCount(Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY))
                .as("because both declarations are rows already, keyed where they were written; "
                    + "an overflow is what a coordinate-keyed grain needs and a position-keyed one "
                    + "does not")
                .isEqualTo(2);
        });
    }

    /** The walk: a merged registry, graphql-java accessors, rows written through the sink. */
    private static void runWalk(DSLContext dsl, Path file) {
        seedSource(dsl, SchemaLoader.DIRECTIVES_SOURCE_NAME);
        seedSource(dsl, file.toString());
        var parse = SchemaLoader.parsePerSource(List.of(SchemaSource.file(file)));
        var sink = new FactSink(dsl, GRAPH, LocalDateTime.now().withNano(0));
        // The walk refuses a source no schema input declared, so it is handed the attribution the
        // derivation does not ask for: the entries key on the source name the parser returned.
        SdlFactCapture.capture(sink, parse.registry(), new ClasspathSources(),
            Map.of(file.toString(), SchemaInput.file(file)), Set.of());
        sink.flush();
    }

    /** The derivation: the entry stratum first, then the anchors selected out of it. */
    private static void runDerivation(DSLContext dsl, Path file) {
        LocalDateTime readAt = LocalDateTime.now().withNano(0);
        seedSource(dsl, SchemaLoader.DIRECTIVES_SOURCE_NAME);
        seedSource(dsl, file.toString());
        SchemaLoader.parsePerSource(List.of(SchemaSource.file(file))).perSource().forEach(document ->
            SdlEntries.write(dsl, GRAPH, document.sourceName(), document.registry(), readAt));
        SdlAnchor.write(dsl, GRAPH, readAt);
    }

    /**
     * One relation's rows as a map from primary key to column values, so a difference is reported
     * as the column it is in rather than as two whole rows a reader has to diff by eye. Excluded
     * from the comparison and named rather than waved at: the instant, because two readings are two
     * instants and that is the point of the column, and the generated columns, which are a function
     * of the row beside them and would report one disagreement twice.
     */
    private static Map<String, Map<String, Object>> keyedRows(DSLContext dsl, Table<?> table) {
        Set<String> generated = generatedColumns(dsl, table);
        List<Field<?>> compared = new ArrayList<>();
        for (Field<?> field : table.fields()) {
            String name = field.getName().toUpperCase(Locale.ROOT);
            if (!EXCLUDED_COLUMNS.contains(name) && !generated.contains(name)) {
                compared.add(field);
            }
        }
        List<Field<?>> key = table.getPrimaryKey() == null
            ? compared
            : new ArrayList<>(table.getPrimaryKey().getFields());
        var rows = new TreeMap<String, Map<String, Object>>();
        for (Record row : dsl.select(compared).from(table).fetch()) {
            var values = new TreeMap<String, Object>();
            compared.forEach(field -> values.put(field.getName().toLowerCase(Locale.ROOT),
                row.get(field.getName())));
            rows.put(key.stream().map(field -> String.valueOf(row.get(field.getName())))
                .collect(Collectors.joining("/")), values);
        }
        return rows;
    }

    /** Columns the schema computes, which no writer sets and neither producer can disagree about. */
    private static Set<String> generatedColumns(DSLContext dsl, Table<?> table) {
        return dsl.fetch("""
                select column_name from information_schema.columns
                 where table_name = ? and is_generated = 'ALWAYS'
                """, table.getName().toUpperCase(Locale.ROOT))
            .stream()
            .map(row -> String.valueOf(row.get(0)).toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void seedSource(DSLContext dsl, String sourceName) {
        var t = Tables.STORE_SOURCE;
        dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.LAST_SEEN, t.READ_AT)
            .values(sourceName, "SCHEMA_FILE", LocalDateTime.now(), LocalDateTime.now())
            .onDuplicateKeyIgnore()
            .execute();
    }

    private static Path write(Path directory, String name, String sdl) {
        try {
            return Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
