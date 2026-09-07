package no.sikt.graphitron.model;

import no.sikt.graphitron.model.sink.FactSink;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.EntryFamilyFixture;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The falsifier the entry migration turns on: two writers over one parse have to produce the same
 * rows before either can be retired.
 *
 * <p>Today the as-written half of {@code graphitron_} is written by the gatherer, which fetches each
 * application back out of the transcription and rebuilds it by parsing the literal
 * {@code AstPrinter} rendered into {@code value_sdl}. Beside it the walk now decodes the same
 * applications from the AST it is holding, into a sink nothing flushes. The two arms differ in
 * exactly one thing, where the {@code graphql.language.Directive} came from, so what this compares
 * is whether that round trip is lossless. Nothing has ever checked that, and its javadoc claims it.
 *
 * <p>The comparison is by relation and by rendered row rather than by count, because a count agrees
 * for a decode that wrote the right number of wrong rows. Rows are sorted before comparing: the
 * gatherer reads its applications ordered by name and the walk meets them in the order the author
 * wrote them, and that difference is real but is not a difference in the population.
 *
 * <p>Two corpora, for two different ways to disagree. The entry fixture applies every directive the
 * decode writes a relation for, so it covers the round trip over every argument shape the decode
 * reads. The extension corpus covers the other axis, an application sitting on an extension rather
 * than on a base declaration, where the walk stands on the site and the gatherer rebuilds it from
 * the three columns the walk wrote.
 *
 * <p>One way to disagree is not covered and cannot be, which is worth saying rather than leaving as
 * a gap somebody finds later. First-wins is decided in the order each arm meets the applications,
 * so two applications at one claim key could in principle be resolved differently. No corpus that
 * reaches this fixture has such a pair: a second application of a single-application directive is
 * what makes a schema fail assembly, and both arms number the ordinals from the same column anyway,
 * the walk computing it and the gatherer reading back what the walk wrote.
 */
class EntryWriterAgreementTest {

    /**
     * Directives on extension sites, which the entry fixture has none of and which is where the two
     * arms could most easily disagree: a type-level application carries its <em>site's</em> position
     * as well as its own, and the walk stands on the site while the gatherer rebuilds it from the
     * three columns the walk wrote. {@code Actor} is bound from an extension for that reason, and
     * {@code Film} carries a chain across a base declaration and an extension so a field-level
     * ordinal has to be right across sites too.
     */
    private static final String EXTENDED = """
        type Query { films: [Film!], actors: [Actor!] }

        type Film @table(name: "film") {
          id: ID!
          title: String @field(name: "title")
        }

        extend type Film {
          ratings: [Rating!] @reference(path: [{table: "film_rating"}]) @splitQuery
        }

        type Actor {
          id: ID!
        }

        extend type Actor @table(name: "actor") {
          fullName: String @field(name: "full_name")
        }

        type Rating @table(name: "film_rating") {
          id: ID!
        }
        """;

    private static final String EXTENDED_GRAPH = "extended";

    @TempDir
    static Path directory;

    private static CapturedStore store;

    /** One store for the class, and the second corpus is a second graph in it rather than a boot. */
    private static CapturedStore store() {
        if (store == null) {
            store = EntryFamilyFixture.capture(directory).andGraph(EXTENDED_GRAPH, EXTENDED);
        }
        return store;
    }

    @AfterAll
    static void closeStore() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    @Test
    @DisplayName("the walk decodes the same entry rows the gatherer does, over every directive")
    void theTwoWritersAgreeOverTheWholeEntryHalf() {
        var captured = store();
        agree(captured, captured.graphName(), captured.entriesDecodedByTheWalk());
    }

    @Test
    @DisplayName("and over a corpus whose directives sit on extension sites")
    void theTwoWritersAgreeOnExtensionSites() {
        var captured = store();
        var walk = CapturedStore.entriesDecodedByTheWalk(captured.dsl(), EXTENDED_GRAPH,
            CapturedStore.registryOf(directory, EXTENDED_GRAPH, EXTENDED),
            List.of(CapturedStore.fixtureFile(directory, EXTENDED_GRAPH)));
        var compared = agree(captured, EXTENDED_GRAPH, walk);

        assertThat(compared.get("graphitron_table"))
            .as("the extension-site binding has to be among what was compared, or the corpus is not "
                + "exercising the axis it is here for")
            .anySatisfy(row -> assertThat(row).contains("Actor"));
    }

    /**
     * Holds the two arms to writing the same rows into every entry relation, and returns the
     * gatherer's rows so a caller can say something further about what it just compared.
     */
    private static Map<String, List<String>> agree(CapturedStore captured, String graphName,
                                                   FactSink walk) {
        var computed = computedColumns(captured.dsl());
        var buffered = new LinkedHashMap<String, List<String>>();
        walk.buffered().forEach((relation, records) -> {
            String name = relation.getName().toLowerCase(Locale.ROOT);
            if (EntryFamilyFixture.ENTRY_RELATIONS.contains(name)) {
                var fields = compared(relation, computed);
                buffered.put(name, records.stream()
                    .map(record -> fields.stream().map(record::get).toList().toString())
                    .sorted().toList());
            }
        });

        var gathered = new LinkedHashMap<String, List<String>>();
        var differing = new ArrayList<String>();
        for (String name : EntryFamilyFixture.ENTRY_RELATIONS) {
            Table<?> relation = relation(name);
            List<String> stored = storedRows(captured, relation, graphName, compared(relation, computed));
            gathered.put(name, stored);
            if (!stored.equals(buffered.getOrDefault(name, List.of()))) {
                differing.add(name + "\n  gatherer: " + stored
                    + "\n  walk:     " + buffered.getOrDefault(name, List.of()));
            }
        }
        assertThat(differing)
            .as("the walk and the gatherer decode one parse into different rows, so the round trip "
                + "through value_sdl is not the identity the move assumes it is")
            .isEmpty();
        return gathered;
    }

    /**
     * The columns of {@code relation} the two arms can be held to, which is every column neither
     * arm writes. A computed column is filled by the store on insert, so the gatherer's row carries
     * a value and the walk's buffered record, which nothing has flushed, carries null; comparing
     * those would fail on every relation with an {@code _upper} column and say nothing about the
     * decode.
     */
    private static List<Field<?>> compared(Table<?> relation, Set<String> computed) {
        var fields = new ArrayList<Field<?>>();
        String table = relation.getName().toUpperCase(Locale.ROOT);
        for (Field<?> field : relation.fields()) {
            if (!computed.contains(table + "." + field.getName().toUpperCase(Locale.ROOT))) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * Which columns the store computes, asked of the store rather than of the generated model. The
     * code generator models a generated column as an ordinary field, so nothing on the jOOQ
     * relation tells the two apart; {@code INFORMATION_SCHEMA} does, and it is the same source the
     * refresh already asks about a relation's shape.
     */
    private static Set<String> computedColumns(DSLContext dsl) {
        return dsl.select(field(name("TABLE_NAME"), String.class), field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("GENERATION_EXPRESSION"), String.class).isNotNull())
            .fetch().stream()
            .map(row -> row.value1() + "." + row.value2())
            .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> storedRows(CapturedStore captured, Table<?> relation, String graphName,
                                           List<Field<?>> fields) {
        Field<String> graph = relation.field("GRAPH_NAME", String.class);
        var select = captured.dsl().select(fields).from(relation);
        var rows = graph == null ? select.fetch() : select.where(graph.eq(graphName)).fetch();
        return rows.stream().map(record -> record.intoList().toString()).sorted().toList();
    }

    private static Table<?> relation(String name) {
        return Public.PUBLIC.getTables().stream()
            .filter(table -> table.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                name + " is listed as an entry relation and the generated model has no such table"));
    }
}
