package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.FactGraphs;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * What holds of any captured graph, whatever it states, checked over every fact document's graph
 * ({@link FactGraphs}): the element anchors agree with their attribute relations, and every source
 * the reading drew on is recorded as the file it is.
 *
 * <p>Each case compares one graph at a time, graphs being the inner loop, so one statement's text
 * repeats with only the graph bound and stays planned; a comparison over a whole store at once holds
 * every graph's rows on the heap. Each also says the population gave it something to compare, since
 * an invariant over nothing passes all the same.
 */
class CapturedGraphInvariantsTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /**
     * An anchor exists exactly where its attribute sibling has a row, in both directions. A foreign
     * key stops an attribute row with no anchor, and nothing at all stops an anchor with no
     * attributes, so a coordinate the walk claims and then declines to describe is caught here
     * rather than read downstream as a type or field with no properties. The element supertype is
     * held to the union of the six anchors the same way, its kind restated from the rule it rests
     * on: a field and an input field share an anchor, and the declaring type's kind tells them apart.
     * The schema block is the supertype's one kind with no anchor, and its coordinate is always
     * {@code $schema}.
     */
    @Test
    @DisplayName("every coordinate anchor pairs with its attribute relation, both ways")
    void coordinatesAgreeWithTheirAttributeRelations() {
        var disagreeing = new ArrayList<String>();
        var kinds = new TreeSet<String>();
        eachGraph((dsl, graph) -> {
            PAIRS.forEach((name, pair) -> {
                // A set operation joins its operand to the select it is called on, so each direction
                // is built afresh rather than from one pair of selects.
                dsl.fetch(pair.anchor().apply(graph).except(pair.attribute().apply(graph)))
                    .forEach(row -> disagreeing.add(graph + " " + name + " anchor only: " + row.intoList()));
                dsl.fetch(pair.attribute().apply(graph).except(pair.anchor().apply(graph)))
                    .forEach(row -> disagreeing.add(graph + " " + name + " attribute only: " + row.intoList()));
            });
            kinds.addAll(dsl.selectDistinct(GRAPHQL_ELEMENT.ELEMENT_KIND).from(GRAPHQL_ELEMENT)
                .where(GRAPHQL_ELEMENT.GRAPH_NAME.eq(graph)).fetch(GRAPHQL_ELEMENT.ELEMENT_KIND));
            // The schema block has no anchor to pair with; its one coordinate is ours, $schema.
            dsl.select(GRAPHQL_ELEMENT.COORDINATE).from(GRAPHQL_ELEMENT)
                .where(GRAPHQL_ELEMENT.GRAPH_NAME.eq(graph), GRAPHQL_ELEMENT.ELEMENT_KIND.eq(SCHEMA),
                    GRAPHQL_ELEMENT.COORDINATE.ne("$schema"))
                .fetch(GRAPHQL_ELEMENT.COORDINATE)
                .forEach(coordinate -> disagreeing.add(graph + " schema element at " + coordinate));
        });
        // Without an input object the field arm's two branches collapse to one and the split it
        // states cannot fail.
        assertThat(kinds).as("the documents reach every kind, or the equalities are partly vacuous")
            .contains("NAMED_TYPE", "FIELD", "INPUT_FIELD", "ENUM_VALUE", "FIELD_ARGUMENT",
                "DIRECTIVE", "DIRECTIVE_ARGUMENT", SCHEMA);
        assertThat(disagreeing).as("coordinates on one side of an anchor pair only").isEmpty();
    }

    /**
     * Every file the reading declared a type from has a source row. The SDL side declares no
     * foreign key into {@code store_source}: a schema-level row can carry a null source name, and
     * the foreign key doctrine puts one only where the writer stands on the parent while writing the
     * child. Reachability is what the partition rule asks for, and these rows are it.
     */
    @Test
    @DisplayName("every schema file a reading declared from has a source row")
    void schemaFilesAreRecordedAsSources() {
        var d = GRAPHQL_TYPE_DECLARATION;
        var s = STORE_SOURCE;
        var unrecorded = new ArrayList<String>();
        int[] declaring = {0};
        eachGraph((dsl, graph) -> {
            declaring[0] += dsl.fetchCount(dsl.selectDistinct(d.SOURCE_NAME).from(d)
                .where(d.GRAPH_NAME.eq(graph), d.SOURCE_NAME.isNotNull()));
            dsl.selectDistinct(d.SOURCE_NAME).from(d)
                .where(d.GRAPH_NAME.eq(graph), d.SOURCE_NAME.isNotNull())
                .andNotExists(selectOne().from(s)
                    .where(s.SOURCE_NAME.eq(d.SOURCE_NAME), s.SOURCE_KIND.eq("SCHEMA_FILE")))
                .fetch(d.SOURCE_NAME)
                .forEach(source -> unrecorded.add(graph + " " + source));
        });
        assertThat(declaring[0]).as("the documents declare types, so this pins something").isPositive();
        assertThat(unrecorded).as("declaring files with no SCHEMA_FILE source row").isEmpty();
    }

    /**
     * The source registry records when each file was last written, which is the one question a
     * content hash cannot answer: {@code stamp} says whether the bytes changed and {@code read_at}
     * when that was established, and neither orders two files. Read where the file is opened to be
     * hashed. Compared with the rendered file as the scenario left it, to the second, which is the
     * resolution the column holds; the bundled specification and directive vocabulary are no files.
     */
    @Test
    @DisplayName("a schema file's modification time is recorded, and it is the file's own")
    void theModificationTimeIsRecorded() {
        var g = STORE_GRAPH_SOURCE;
        var s = STORE_SOURCE;
        var wrong = new ArrayList<String>();
        int[] files = {0};
        eachGraph((dsl, graph) -> dsl.select(s.SOURCE_NAME, s.MTIME).from(g)
            .join(s).on(s.SOURCE_NAME.eq(g.SOURCE_NAME))
            .where(g.GRAPH_NAME.eq(graph), s.SOURCE_KIND.eq("SCHEMA_FILE"))
            .fetch()
            .forEach(row -> {
                var file = Path.of(row.value1());
                if (file.isAbsolute() && Files.isRegularFile(file)) {
                    files[0]++;
                    var onDisk = onDisk(file);
                    if (!onDisk.equals(row.value2())) {
                        wrong.add(graph + " " + file + ": recorded " + row.value2() + ", on disk " + onDisk);
                    }
                }
            }));
        assertThat(files[0]).as("the documents' rendered files are source rows, so this pins something")
            .isPositive();
        assertThat(wrong).as("schema files whose recorded modification time is not the file's").isEmpty();
    }

    /** The supertype's kind for the schema block, the one element no anchor relation describes. */
    private static final String SCHEMA = "SCHEMA";

    /** One anchor and the attribute relation it pairs with, each the graph's rows of one shape. */
    private record Pair(Function<String, Select<Record>> anchor, Function<String, Select<Record>> attribute) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Select<Record> rows(Select<? extends Record> select) {
        return (Select) select;
    }

    /**
     * The pairs, named so a reader sees which relations the pairing names; the pairing is a
     * modelling decision rather than something to find in the DDL.
     */
    private static final Map<String, Pair> PAIRS = pairs();

    private static Map<String, Pair> pairs() {
        var pairs = new java.util.LinkedHashMap<String, Pair>();
        pairs.put("type", new Pair(
            graph -> rows(select(GRAPHQL_TYPE_ELEMENT.TYPE_NAME).from(GRAPHQL_TYPE_ELEMENT)
                .where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(graph))),
            graph -> rows(select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .where(GRAPHQL_TYPE.GRAPH_NAME.eq(graph)))));
        pairs.put("field", new Pair(
            graph -> rows(select(GRAPHQL_FIELD_ELEMENT.TYPE_NAME, GRAPHQL_FIELD_ELEMENT.FIELD_NAME)
                .from(GRAPHQL_FIELD_ELEMENT).where(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME.eq(graph))),
            graph -> rows(select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME)
                .from(GRAPHQL_FIELD).where(GRAPHQL_FIELD.GRAPH_NAME.eq(graph)))));
        pairs.put("argument", new Pair(
            graph -> rows(select(GRAPHQL_ARGUMENT_ELEMENT.TYPE_NAME, GRAPHQL_ARGUMENT_ELEMENT.FIELD_NAME,
                    GRAPHQL_ARGUMENT_ELEMENT.ARGUMENT_NAME)
                .from(GRAPHQL_ARGUMENT_ELEMENT).where(GRAPHQL_ARGUMENT_ELEMENT.GRAPH_NAME.eq(graph))),
            graph -> rows(select(GRAPHQL_ARGUMENT.TYPE_NAME, GRAPHQL_ARGUMENT.FIELD_NAME,
                    GRAPHQL_ARGUMENT.ARGUMENT_NAME)
                .from(GRAPHQL_ARGUMENT).where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graph)))));
        pairs.put("enum value", new Pair(
            graph -> rows(select(GRAPHQL_ENUM_VALUE_ELEMENT.TYPE_NAME, GRAPHQL_ENUM_VALUE_ELEMENT.VALUE_NAME)
                .from(GRAPHQL_ENUM_VALUE_ELEMENT).where(GRAPHQL_ENUM_VALUE_ELEMENT.GRAPH_NAME.eq(graph))),
            graph -> rows(select(GRAPHQL_ENUM_VALUE.TYPE_NAME, GRAPHQL_ENUM_VALUE.VALUE_NAME)
                .from(GRAPHQL_ENUM_VALUE).where(GRAPHQL_ENUM_VALUE.GRAPH_NAME.eq(graph)))));
        pairs.put("directive", new Pair(
            graph -> rows(select(GRAPHQL_DIRECTIVE_ELEMENT.DIRECTIVE_NAME)
                .from(GRAPHQL_DIRECTIVE_ELEMENT).where(GRAPHQL_DIRECTIVE_ELEMENT.GRAPH_NAME.eq(graph))),
            graph -> rows(select(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME)
                .from(GRAPHQL_DIRECTIVE).where(GRAPHQL_DIRECTIVE.GRAPH_NAME.eq(graph)))));
        pairs.put("directive argument", new Pair(
            graph -> rows(select(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT.DIRECTIVE_NAME,
                    GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT.ARGUMENT_NAME)
                .from(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT)
                .where(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT.GRAPH_NAME.eq(graph))),
            graph -> rows(select(GRAPHQL_DIRECTIVE_ARGUMENT.DIRECTIVE_NAME,
                    GRAPHQL_DIRECTIVE_ARGUMENT.ARGUMENT_NAME)
                .from(GRAPHQL_DIRECTIVE_ARGUMENT).where(GRAPHQL_DIRECTIVE_ARGUMENT.GRAPH_NAME.eq(graph)))));
        // The supertype against the six it generalises. This direction is the one no constraint
        // reaches: a foreign key refuses an anchor with no supertype row and nothing refuses a
        // supertype row no anchor claimed. The schema block is the supertype's seventh kind and has
        // no anchor of its own, so it is held apart below.
        pairs.put("element", new Pair(
            graph -> rows(select(GRAPHQL_ELEMENT.COORDINATE, GRAPHQL_ELEMENT.ELEMENT_KIND)
                .from(GRAPHQL_ELEMENT)
                .where(GRAPHQL_ELEMENT.GRAPH_NAME.eq(graph), GRAPHQL_ELEMENT.ELEMENT_KIND.ne(SCHEMA))),
            graph -> rows(select(GRAPHQL_TYPE_ELEMENT.COORDINATE, val("NAMED_TYPE").as("element_kind"))
                    .from(GRAPHQL_TYPE_ELEMENT).where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(graph))
                .unionAll(select(GRAPHQL_FIELD_ELEMENT.COORDINATE,
                        when(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT"), val("INPUT_FIELD"))
                            .otherwise(val("FIELD")).as("element_kind"))
                    .from(GRAPHQL_FIELD_ELEMENT)
                    .join(GRAPHQL_TYPE)
                    .on(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME))
                    .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD_ELEMENT.TYPE_NAME))
                    .where(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME.eq(graph)))
                .unionAll(select(GRAPHQL_ARGUMENT_ELEMENT.COORDINATE, val("FIELD_ARGUMENT").as("element_kind"))
                    .from(GRAPHQL_ARGUMENT_ELEMENT).where(GRAPHQL_ARGUMENT_ELEMENT.GRAPH_NAME.eq(graph)))
                .unionAll(select(GRAPHQL_ENUM_VALUE_ELEMENT.COORDINATE, val("ENUM_VALUE").as("element_kind"))
                    .from(GRAPHQL_ENUM_VALUE_ELEMENT).where(GRAPHQL_ENUM_VALUE_ELEMENT.GRAPH_NAME.eq(graph)))
                .unionAll(select(GRAPHQL_DIRECTIVE_ELEMENT.COORDINATE, val("DIRECTIVE").as("element_kind"))
                    .from(GRAPHQL_DIRECTIVE_ELEMENT).where(GRAPHQL_DIRECTIVE_ELEMENT.GRAPH_NAME.eq(graph)))
                .unionAll(select(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT.COORDINATE,
                        val("DIRECTIVE_ARGUMENT").as("element_kind"))
                    .from(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT)
                    .where(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT.GRAPH_NAME.eq(graph))))));
        return java.util.Collections.unmodifiableMap(pairs);
    }

    /** Runs {@code body} over each graph of the population, every document captured first. */
    private static void eachGraph(java.util.function.BiConsumer<DSLContext, String> body) {
        FACTS.warm(FACTS.paths());
        FACTS.eachStore((dsl, graphs) -> {
            graphs.forEach(graph -> body.accept(dsl, graph));
            return null;
        });
    }

    /** What the filesystem says, to the second, which is the resolution the column is compared at. */
    private static LocalDateTime onDisk(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault())
                .withNano(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
