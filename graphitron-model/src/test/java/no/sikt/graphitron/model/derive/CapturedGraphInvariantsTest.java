package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.sink.WriteTimes;
import no.sikt.graphitron.model.test.FactGraphs;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import static no.sikt.graphitron.model.Tables.META_RELATION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_PROGRESS;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_READ;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_WRITE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_WRITE_STAGE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * What holds of any captured graph, whatever it states, checked over every fact document's graph
 * ({@link FactGraphs}): the element anchors agree with their attribute relations, every source the
 * reading drew on is recorded as the file it is, the capture timed its own writes and reads, and it
 * completed every gatherer it ran.
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

    /**
     * A finished capture records how long it spent writing each table, under the gatherer at work,
     * which is the one the table's declaration names as its owner: a write timed under another
     * gatherer puts its cost where nobody looks for it. The run's own record of itself, its
     * progress and its timings, is not part of what it times.
     */
    @Test
    @DisplayName("a relation's write is timed under the gatherer that owns it, the run's own rows are not")
    void writesAreTimedUnderTheirOwner() {
        var w = STORE_GRAPH_WRITE;
        var r = STORE_GRAPH_READ;
        var m = META_RELATION;
        var elsewhere = new TreeSet<String>();
        var own = new ArrayList<String>();
        int[] owned = {0};
        eachGraph((dsl, graph) -> {
            owned[0] += dsl.fetchCount(dsl.select(w.TABLE_NAME).from(w)
                .join(m).on(m.RELATION_NAME.eq(lower(w.TABLE_NAME)))
                .where(w.GRAPH_NAME.eq(graph)));
            dsl.selectDistinct(w.TABLE_NAME, w.GATHERER_NAME).from(w)
                .join(m).on(m.RELATION_NAME.eq(lower(w.TABLE_NAME)))
                .where(w.GRAPH_NAME.eq(graph), w.GATHERER_NAME.ne(m.OWNER_NAME))
                .fetch()
                .forEach(row -> elsewhere.add(row.value1() + " under " + row.value2()));
            dsl.select(w.TABLE_NAME).from(w)
                .where(w.GRAPH_NAME.eq(graph), w.TABLE_NAME.in(RUN_RECORD))
                .fetch(w.TABLE_NAME)
                .forEach(table -> own.add(graph + " the run's own " + table + " timed as a write"));
            dsl.select(r.RELATION_NAME).from(r)
                .where(r.GRAPH_NAME.eq(graph), r.RELATION_NAME.in(RUN_RECORD))
                .fetch(r.RELATION_NAME)
                .forEach(relation -> own.add(graph + " the run's own " + relation + " timed as a read"));
        });
        assertThat(owned[0]).as("the captures write declared relations, so this pins something")
            .isPositive();
        assertThat(own).as("the run's own record timed as part of the run").isEmpty();
        assertThat(elsewhere).as("writes timed under a gatherer other than the table's declared owner")
            .isSubsetOf(TIMED_ELSEWHERE.keySet());
        assertThat(TIMED_ELSEWHERE.keySet())
            .as("a known write now timed under its owner: take it off the list")
            .isSubsetOf(elsewhere);
    }

    /**
     * Writes timed under a gatherer other than the one {@code meta_relation} declares the table's
     * owner when this case landed, as table and gatherer, each beside the owner it is declared to.
     * Not yet ruled on: either the declaration names the gatherer that writes, or the write moves
     * into the owner's window.
     */
    private static final Map<String, String> TIMED_ELSEWHERE = Map.of(
        "INTENT_TYPE_DOMAIN under derivation", "declared the sdl gatherer's",
        "GRAPHQL_SCHEMA_PROBLEM under graphql-source", "declared the graphql-assembly gatherer's;"
            + " the source reading writes what would not parse",
        "STORE_CLASS_FILE under code", "declared the classpath-source gatherer's");

    /**
     * A stage that fetches its rule into Java and inserts the rows evaluates the rule in the read, so
     * the reads are timed beside the writes, under the instant the capture's writes carry, and the
     * slowest statements of a graph name both, from its latest capture alone. A read under a
     * millisecond, its preparing included, is left out, which the relation's own check holds. H2
     * parses and plans a statement before it runs, which for a rule of many arms can cost more than
     * running it, so preparing is counted apart, for each table and for each anchor stage.
     */
    @Test
    @DisplayName("a capture's reads are timed beside its writes, preparing apart, the slowest from the latest")
    void readsAreTimedBesideWrites() {
        var w = STORE_GRAPH_WRITE;
        var r = STORE_GRAPH_READ;
        var s = STORE_GRAPH_WRITE_STAGE;
        var wrong = new ArrayList<String>();
        long[] totals = new long[3];
        var kinds = new TreeSet<WriteTimes.Kind>();
        eachGraph((dsl, graph) -> {
            totals[0] += dsl.fetchCount(r, r.GRAPH_NAME.eq(graph));
            totals[1] += dsl.select(coalesce(sum(w.PREPARE_MS), BigDecimal.ZERO)).from(w)
                .where(w.GRAPH_NAME.eq(graph)).fetchSingle().value1().longValue();
            totals[2] += dsl.select(coalesce(sum(s.PREPARE_MS), BigDecimal.ZERO)).from(s)
                .where(s.GRAPH_NAME.eq(graph)).fetchSingle().value1().longValue();
            dsl.selectDistinct(r.CAPTURED_AT).from(r)
                .where(r.GRAPH_NAME.eq(graph))
                .andNotExists(selectOne().from(w)
                    .where(w.GRAPH_NAME.eq(graph), w.CAPTURED_AT.eq(r.CAPTURED_AT)))
                .fetch(r.CAPTURED_AT)
                .forEach(at -> wrong.add(graph + " reads at " + at + ", when no capture wrote"));
            var latest = dsl.select(max(w.CAPTURED_AT)).from(w).where(w.GRAPH_NAME.eq(graph))
                .fetchSingle().value1();
            var slowest = WriteTimes.slowest(dsl, graph, Integer.MAX_VALUE);
            slowest.forEach(statement -> kinds.add(statement.kind()));
            int ofTheLatest = dsl.fetchCount(w, w.GRAPH_NAME.eq(graph), w.CAPTURED_AT.eq(latest))
                + dsl.fetchCount(r, r.GRAPH_NAME.eq(graph), r.CAPTURED_AT.eq(latest));
            if (slowest.size() != ofTheLatest) {
                wrong.add(graph + " slowest names " + slowest.size() + " statements, its latest capture"
                    + " timed " + ofTheLatest);
            }
        });
        assertThat(wrong).as("reads timed apart from the capture that ran them").isEmpty();
        assertThat(totals[0]).as("the captures read for a millisecond somewhere").isPositive();
        assertThat(totals[1]).as("the captures plan their statements for a millisecond somewhere")
            .isPositive();
        assertThat(totals[2]).as("the anchor stages' planning is counted under each stage too")
            .isPositive();
        assertThat(kinds).as("the slowest name reads and writes alike")
            .contains(WriteTimes.Kind.READ, WriteTimes.Kind.WRITE);
    }

    /**
     * A reading that runs to its end completes every gatherer's progress row and the capture's own:
     * each row's last completion is after its last start, so it says how long that reading took.
     * Whatever a document states, its capture ran to its end, so every graph of the population has a
     * completed row for each gatherer a capture runs, and none left open.
     */
    @Test
    @DisplayName("a reading that runs to its end completes every gatherer's row and the capture's")
    void everyFinishedReadingCompletesItsProgress() {
        var p = STORE_GRAPH_PROGRESS;
        var wrong = new ArrayList<String>();
        int[] graphs = {0};
        eachGraph((dsl, graph) -> {
            graphs[0]++;
            var rows = dsl.select(p.GATHERER_NAME, p.TIME_SPENT_MS).from(p)
                .where(p.GRAPH_NAME.eq(graph)).fetchMap(p.GATHERER_NAME, p.TIME_SPENT_MS);
            if (!rows.keySet().equals(GATHERERS)) {
                wrong.add(graph + " has progress rows for " + new TreeSet<>(rows.keySet()));
            }
            rows.forEach((gatherer, spent) -> {
                if (spent == null) {
                    wrong.add(graph + " " + gatherer + " left open");
                }
            });
        });
        assertThat(graphs[0]).as("the population holds graphs").isPositive();
        assertThat(wrong).as("graphs whose finished reading did not complete every progress row")
            .isEmpty();
    }

    /** Every gatherer a capture runs, and the capture as a whole. */
    private static final Set<String> GATHERERS = Set.of("capture", "store", "jooq",
        "classpath-source", "code", "graphql-source", "graphql-ast", "federation", "sdl",
        "graphitron", "graphql-assembly", "derivation");

    /** The run's own record of itself, which a capture writes and does not time. */
    private static final List<String> RUN_RECORD = List.of("STORE_GRAPH_PROGRESS", "STORE_GRAPH_WRITE",
        "STORE_GRAPH_WRITE_STAGE", "STORE_GRAPH_READ");

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
