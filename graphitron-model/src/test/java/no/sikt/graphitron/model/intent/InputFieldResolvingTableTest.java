package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_INPUT_FIELD_RESOLVING_TABLE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_input_field_resolving_table} answers: which table an input field is classified
 * against. The pair of coordinate and table is the grain, and the cases here are about that pair
 * rather than about either half of it, because the pair is the whole reason the relation exists.
 *
 * <p>An input field does not own a table. It is handed one by whatever argument reached it, so the
 * same declaration classified under two arguments whose fields select from different tables is two
 * classifications and two answers, and a relation keyed on the coordinate alone could state neither
 * of them. The cases assert that in both directions: two use sites over one table collapse to one
 * row, and two use sites over two tables stay two.
 *
 * <p>The other half of the subject is that nesting does not re-root. The classifier descends into a
 * nested input object carrying the same table down, so a field three levels below the argument
 * answers with the argument's table and not with anything nearer. That is stated at depth rather
 * than assumed from a depth-one case, a rule that re-rooted somewhere would still pass at depth one.
 */
class InputFieldResolvingTableTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    private static final OccurrenceStep TITLE = new OccurrenceStep("FilmFilter", "title", "String");
    private static final OccurrenceStep NESTED = new OccurrenceStep("FilmFilter", "nested", "Deep");
    private static final OccurrenceStep DEEP = new OccurrenceStep("Deep", "note", "String");

    // ===== The table comes from the consuming field =====

    /**
     * The ordinary case: an input-object argument on a field selecting from {@code film} classifies
     * its input type's fields against {@code film}. The table is the consuming field's and reaches
     * the input field through the argument, which is the whole shape of the rule.
     */
    @Test
    void anInputFieldResolvesAgainstTheConsumingFieldsTable() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);

            assertThat(rows(dsl).map(InputFieldResolvingTableTest::render))
                .containsExactly("FilmFilter.title film");
        });
    }

    /**
     * The argument's own occurrence is not an input field and gets no row. Depth 0 is the argument
     * standing for itself, whose table is {@code intent_argument_scope_table}'s answer and already
     * has a relation; repeating it here would make the grain half coordinate and half argument.
     */
    @Test
    void theArgumentsOwnOccurrenceIsNotAnInputField() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * Nesting carries the table down unchanged. The classifier's descent hands the nested input
     * object the same resolving table it was given, and a {@code @table} on an input object is
     * captured and ignored, so there is nothing at any depth that could re-root the walk. Stated at
     * depth two so a rule re-rooting on the nested type would fail here rather than pass.
     */
    @Test
    void nestingCarriesTheTableDownUnchanged() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedInputField(dsl, GRAPH, "FilmFilter", "nested", "Deep", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Deep", "note", "String", 0, false, false, null);
            seedTableBinding(dsl, GRAPH, "Deep", "actor");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", NESTED, DEEP);

            assertThat(rows(dsl).map(InputFieldResolvingTableTest::render)).containsExactly(
                "Deep.note film",
                "FilmFilter.nested film");
        });
    }

    /**
     * A mutation's payload argument resolves the same way, its field's table coming from the
     * {@code @mutation(table:)} spelling rather than from a bound return type. Both entry points
     * into the input-field classifier hand it the consuming field's own table, so this relation has
     * one rule and not two, and the rung that produced the table is the scope relation's business.
     */
    @Test
    void aMutationPayloadResolvesAgainstTheMutationsTable() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Status", "OBJECT");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Status", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE", "film");
            seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "input", "FilmInput");
            seedInputField(dsl, GRAPH, "FilmInput", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "input", "FilmInput",
                new OccurrenceStep("FilmInput", "title", "String"));

            assertThat(rows(dsl).map(InputFieldResolvingTableTest::render))
                .containsExactly("FilmInput.title film");
        });
    }

    // ===== The grain, which is what this relation is for =====

    /**
     * One input type reached from two fields selecting from different tables is two classifications
     * and two rows. This is the case a coordinate-keyed relation could not state at all, and it is
     * why the table is part of the key rather than a column beside it.
     */
    @Test
    void oneInputTypeUnderTwoTablesIsTwoRows() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedField(dsl, GRAPH, "Query", "actors", "Actor", true);
            seedArgument(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);
            seedOccurrencePath(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter", TITLE);

            assertThat(rows(dsl).map(InputFieldResolvingTableTest::render)).containsExactly(
                "FilmFilter.title actor",
                "FilmFilter.title film");
        });
    }

    /**
     * Two use sites over one table are one row, which is the other half of the same claim. The
     * classifier performs one classification per pair and this relation is that population, so an
     * occurrence-keyed relation would hand every consumer a duplicate to fold away.
     */
    @Test
    void twoUseSitesOverOneTableCollapseToOneRow() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedField(dsl, GRAPH, "Query", "otherFilms", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "otherFilms", "filter", "FilmFilter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);
            seedOccurrencePath(dsl, GRAPH, "Query", "otherFilms", "filter", "FilmFilter", TITLE);

            var rows = rows(dsl);
            assertThat(rows).hasSize(1);
            assertThat(render(rows.getFirst())).isEqualTo("FilmFilter.title film");
        });
    }

    // ===== Where the relation declines =====

    /**
     * A field returning a type nothing binds hands its expansion no table, so nothing under it
     * resolves. The decline is inherited from the scope relation rather than restated, which is why
     * this relation reads it instead of spelling its rungs again.
     */
    @Test
    void anArgumentOnAnUnboundFieldResolvesNothingBeneathIt() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Report", "OBJECT");
            seedField(dsl, GRAPH, "Query", "report", "Report", false);
            seedArgument(dsl, GRAPH, "Query", "report", "filter", "FilmFilter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "report", "filter", "FilmFilter", TITLE);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * An input type no use site names has no row, the domain being the reach of the occurrence
     * relation and not the declared input surface. An input object nobody passes is classified
     * against nothing, so there is no table to state.
     */
    @Test
    void anInputTypeNoArgumentReachesHasNoRow() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedInputField(dsl, GRAPH, "Unused", "title", "String", 0, false, false, null);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's occurrences resolve none of this one's fields. */
    @Test
    void aSiblingGraphResolvesNoneOfTheseFields() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
                seedColumn(dsl, PKG, PUBLIC, table, "title", 1, "TITLE");
            }
            seedType(dsl, GRAPH, "String", "SCALAR");
            body.accept(dsl);
        });
    }

    /** A list field on {@code Query} returning a {@code film}-bound type, carrying one argument. */
    private static void filmQuery(DSLContext dsl, String fieldName, String argumentName) {
        seedTableBinding(dsl, GRAPH, "Film", "film");
        seedField(dsl, GRAPH, "Query", fieldName, "Film", true);
        seedArgument(dsl, GRAPH, "Query", fieldName, argumentName, "FilmFilter");
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_INPUT_FIELD_RESOLVING_TABLE.fields())
            .from(INTENT_INPUT_FIELD_RESOLVING_TABLE)
            .where(INTENT_INPUT_FIELD_RESOLVING_TABLE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_INPUT_FIELD_RESOLVING_TABLE.TYPE_NAME,
                INTENT_INPUT_FIELD_RESOLVING_TABLE.FIELD_NAME,
                INTENT_INPUT_FIELD_RESOLVING_TABLE.TABLE_NAME)
            .fetch();
    }

    /** The input coordinate and the table it is classified against: the claim of every case here. */
    private static String render(Record row) {
        return row.get(INTENT_INPUT_FIELD_RESOLVING_TABLE.TYPE_NAME) + "."
            + row.get(INTENT_INPUT_FIELD_RESOLVING_TABLE.FIELD_NAME) + " "
            + row.get(INTENT_INPUT_FIELD_RESOLVING_TABLE.TABLE_NAME);
    }
}
