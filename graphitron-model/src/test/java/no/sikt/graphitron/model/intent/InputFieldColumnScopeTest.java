package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_INPUT_FIELD_COLUMN_SCOPE;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_FIELD_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_input_field_column_scope} resolves: the table a column name written at an
 * input field resolves against, which is the table the binding built from that field lands on.
 *
 * <p>The cases are organised by rule and every one of them asserts which rule answered rather than
 * only that a table came out. The two rules reach the same table at most sites, so a case asserting
 * the table alone would pass with the rules swapped, and which rule answered is what a consumer
 * takes: it is also the fork between a binding on the table the consuming field already selects
 * from and one a join away.
 *
 * <p>Two things separate this site from the argument site whose relation this mirrors, and both get
 * cases. The departure is not a function of the coordinate, so one input field carrying one path is
 * two rows when two arguments classify it against two tables and the path walks from each. And an
 * input object binds no table of its own, so a path here departs from a table the field was handed
 * rather than from anything the enclosing type declares, which is what a case seeding a binding on
 * the input type states by getting the same answer with and without it.
 *
 * <p>Where this relation declines, the decline gets a case of its own stating the absence rather
 * than being left to be inferred from a passing neighbour.
 */
class InputFieldColumnScopeTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    private static final OccurrenceStep TITLE = new OccurrenceStep("FilmFilter", "title", "String");
    private static final OccurrenceStep IN_ACTOR =
        new OccurrenceStep("FilmFilter", "inActor", "String");

    // ===== RESOLVING_TABLE =====

    /**
     * The ordinary case: the field writes no path, so its name resolves on the table the classifier
     * handed the expansion, which is the consuming field's own.
     */
    @Test
    void anInputFieldWithNoPathResolvesOnTheTableItWasHanded() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);

            assertThat(rows(dsl).map(InputFieldColumnScopeTest::render))
                .containsExactly("FilmFilter.title@film RESOLVING_TABLE film");
        });
    }

    /**
     * An element-less {@code @reference(path: [])} is legal SDL and inert, and this relation reads
     * it the way the classifier does. The classifier routes such a field through its reference arm
     * and resolves the empty path on the table it was handed, which is where the directive-less
     * field would have resolved, so the anti-join is on the path's elements and never on the
     * directive's presence.
     */
    @Test
    void anElementLessReferenceIsInertAndLeavesTheResolvingTableStanding() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedFieldReference(dsl, GRAPH, "FilmFilter", "title", 0);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);

            assertThat(rows(dsl).map(InputFieldColumnScopeTest::render))
                .containsExactly("FilmFilter.title@film RESOLVING_TABLE film");
        });
    }

    /**
     * A binding on the input object itself changes nothing, which is what makes the departure the
     * consuming site's rather than the enclosing type's. A {@code @table} on an input object is
     * captured and ignored by the classifier, and the rule here reads the handed table alone, so
     * the answer with the binding equals the answer without it.
     */
    @Test
    void aBindingOnTheInputTypeItselfDoesNotMoveTheSite() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedTableBinding(dsl, GRAPH, "FilmFilter", "actor");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);

            assertThat(rows(dsl).map(InputFieldColumnScopeTest::render))
                .containsExactly("FilmFilter.title@film RESOLVING_TABLE film");
        });
    }

    // ===== PATH_TERMINAL =====

    /**
     * A written path moves the site, and it moves it to where the path ends. Two elements rather
     * than one, because a single-element case cannot tell the terminal apart from the first.
     */
    @Test
    void anAuthoredPathResolvesOnItsTerminalElementAndNotItsFirst() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedPathField(dsl, "inActor", "film_actor", "actor");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", IN_ACTOR);

            assertThat(rows(dsl).map(InputFieldColumnScopeTest::render))
                .containsExactly("FilmFilter.inActor@film PATH_TERMINAL actor");
        });
    }

    /**
     * A path is one rule and the handed table is the other, and they are disjoint rather than
     * ranked: a site whose path resolves gets the terminal alone, never the terminal beside the
     * table the field was classified against. Disjointness is what lets this relation be a plain
     * union with no windowed collapse over it, so the property is asserted as an arity here.
     */
    @Test
    void aResolvedPathIsTheSitesOnlyRow() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedPathField(dsl, "inActor", "film_actor");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", IN_ACTOR);

            var rows = rows(dsl);
            assertThat(rows).hasSize(1);
            assertThat(render(rows.getFirst()))
                .isEqualTo("FilmFilter.inActor@film PATH_TERMINAL film_actor");
        });
    }

    /**
     * The departure is part of the grain, and this is the case that makes it earn the column. One
     * authored path on one input field, classified under two arguments whose fields select from two
     * tables, walks from each and the two walks disagree: {@code film} declares a foreign key to
     * {@code language} and {@code actor} declares none, so the same spelling resolves under one
     * argument and reaches nothing under the other. A relation keyed on the coordinate alone would
     * have to pick one of those two answers and would be wrong at the site it did not pick.
     */
    @Test
    void onePathClassifiedAgainstTwoTablesWalksFromEach() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedField(dsl, GRAPH, "Query", "actors", "Actor", true);
            seedArgument(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter");
            seedPathField(dsl, "inLanguage", "language");
            var step = new OccurrenceStep("FilmFilter", "inLanguage", "String");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", step);
            seedOccurrencePath(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter", step);

            assertThat(rows(dsl).map(InputFieldColumnScopeTest::render))
                .containsExactly("FilmFilter.inLanguage@film PATH_TERMINAL language");
        });
    }

    /**
     * {@code film} declares two foreign keys to {@code language}, so the element reaches one
     * destination by two routes. The rule demands a single destination and not a single route, so
     * this resolves, and it resolves once: the arm keeps only the table and takes it distinct.
     */
    @Test
    void anElementReachingOneTableByTwoRoutesIsOneRow() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedPathField(dsl, "inLanguage", "language");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "inLanguage", "String"));

            assertThat(rows(dsl).map(InputFieldColumnScopeTest::render))
                .containsExactly("FilmFilter.inLanguage@film PATH_TERMINAL language");
        });
    }

    // ===== Where certainty is refused =====

    /**
     * A spelling two schemas both declare reaches two destinations, and two destinations are two
     * different bindings. The row is absent rather than picked, and the case asserts the absence
     * whole: a path that reaches nowhere certain must not fall back on the handed table, because
     * resolving a name there points the author at the wrong end of a join.
     */
    @Test
    void aTerminalReachingTwoTablesResolvesNowhereAndDoesNotFallBack() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedPathField(dsl, "inVenue", "venue");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "inVenue", "String"));

            assertThat(rows(dsl)).isEmpty();
            assertThat(dsl.select(INTENT_INPUT_FIELD_REFERENCE_STEP_TARGET.TARGETS)
                    .from(INTENT_INPUT_FIELD_REFERENCE_STEP_TARGET)
                    .where(INTENT_INPUT_FIELD_REFERENCE_STEP_TARGET.GRAPH_NAME.eq(GRAPH))
                    .fetch(INTENT_INPUT_FIELD_REFERENCE_STEP_TARGET.TARGETS))
                .as("the premise of the absence above: the element resolved, and it resolved to two"
                    + " destinations. Without this the case would pass just as well on a path that"
                    + " reached nothing at all, which is a different rule declining")
                .containsExactly(2, 2);
        });
    }

    /**
     * Repetition is a conflict here and not a chain, as at the argument site and unlike at the
     * output-field site: ordered composition has no meaning on an input field, so the classifier
     * rejects a second application outright and there is no first application for this relation to
     * prefer. The absence is the repetition's and not the path's, the case above resolving this
     * same first application on its own.
     */
    @Test
    void aRepeatedReferenceIsAConflictRatherThanAChain() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedPathField(dsl, "inActor", "film_actor");
            seedApplication(dsl, "inActor", 1, "language");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", IN_ACTOR);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * The count that decides the conflict is over the applications and not over their elements, so
     * an empty one written beside a real one is the same conflict, and two empty ones are too. The
     * second is what an anti-join on elements alone would let through as though nothing had been
     * written, which is not what the author wrote and not what the classifier reads.
     */
    @Test
    void twoApplicationsAreTheSameConflictWhicheverCarriesElements() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedFieldReference(dsl, GRAPH, "FilmFilter", "title", 0);
            seedFieldReference(dsl, GRAPH, "FilmFilter", "title", 1);
            seedPathField(dsl, "inActor", "film_actor");
            seedApplication(dsl, "inActor", 1);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", IN_ACTOR);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A field returning a type nothing binds hands its expansion no table, so nothing under it
     * resolves. The decline is inherited rather than restated, which is the point of reading the
     * resolving-table relation instead of spelling its rungs again.
     */
    @Test
    void anArgumentOnAnUnboundFieldResolvesNoneOfItsInputFields() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Report", "OBJECT");
            seedField(dsl, GRAPH, "Query", "report", "Report", false);
            seedArgument(dsl, GRAPH, "Query", "report", "filter", "FilmFilter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "report", "filter", "FilmFilter", TITLE);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's coordinates resolve none of this one's sites. */
    @Test
    void aSiblingGraphResolvesNoneOfTheseSites() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    /**
     * {@code film} reaches {@code film_actor} and {@code language}, the latter by two foreign keys,
     * and {@code venue} is declared in two schemas so a spelling of it reaches two destinations.
     * Every shape the rules decide between is a catalog state, which is why the catalog is stated as
     * rows rather than generated from DDL that would never produce them.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor", "language", "film_actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            for (String schema : List.of(PUBLIC, "archive")) {
                seedTable(dsl, PKG, schema, "venue");
                seedConstraint(dsl, PKG, schema, "venue", "venue_pkey", "PRIMARY KEY", null);
                foreignKey(dsl, "film", "film_venue_id_" + schema + "_fkey", schema, "venue");
            }
            foreignKey(dsl, "film", "film_language_id_fkey", PUBLIC, "language");
            foreignKey(dsl, "film", "film_original_language_id_fkey", PUBLIC, "language");
            foreignKey(dsl, "film_actor", "film_actor_film_id_fkey", PUBLIC, "film");
            foreignKey(dsl, "film_actor", "film_actor_actor_id_fkey", PUBLIC, "actor");
            seedType(dsl, GRAPH, "String", "SCALAR");
            body.accept(dsl);
        });
    }

    /** One foreign key from {@code table} to the primary key of a table in {@code referencedSchema}. */
    private static void foreignKey(DSLContext dsl, String table, String constraintName,
                                   String referencedSchema, String referencedTable) {
        seedConstraint(dsl, PKG, PUBLIC, table, constraintName, "FOREIGN KEY", null);
        seedReferentialConstraint(dsl, PKG, PUBLIC, table, constraintName,
            PKG, referencedSchema, referencedTable, referencedTable + "_pkey");
    }

    /** A list field on {@code Query} returning a {@code film}-bound type, carrying one argument. */
    private static void filmQuery(DSLContext dsl, String fieldName, String argumentName) {
        seedTableBinding(dsl, GRAPH, "Film", "film");
        seedField(dsl, GRAPH, "Query", fieldName, "Film", true);
        seedArgument(dsl, GRAPH, "Query", fieldName, argumentName, "FilmFilter");
    }

    /** An input field carrying one {@code @reference} whose elements each spell a table. */
    private static void seedPathField(DSLContext dsl, String fieldName, String... tableRefs) {
        seedInputField(dsl, GRAPH, "FilmFilter", fieldName, "String", 0, false, false, null);
        seedApplication(dsl, fieldName, 0, tableRefs);
    }

    /** One more {@code @reference} application on an input field that already carries one. */
    private static void seedApplication(DSLContext dsl, String fieldName, int ordinal,
                                        String... tableRefs) {
        seedFieldReference(dsl, GRAPH, "FilmFilter", fieldName, ordinal);
        for (int position = 0; position < tableRefs.length; position++) {
            seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", fieldName, ordinal, position,
                tableRefs[position], null);
        }
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_INPUT_FIELD_COLUMN_SCOPE.fields())
            .from(INTENT_INPUT_FIELD_COLUMN_SCOPE)
            .where(INTENT_INPUT_FIELD_COLUMN_SCOPE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_INPUT_FIELD_COLUMN_SCOPE.TYPE_NAME,
                INTENT_INPUT_FIELD_COLUMN_SCOPE.FIELD_NAME,
                INTENT_INPUT_FIELD_COLUMN_SCOPE.RESOLVING_TABLE,
                INTENT_INPUT_FIELD_COLUMN_SCOPE.TABLE_SCHEMA,
                INTENT_INPUT_FIELD_COLUMN_SCOPE.TABLE_NAME)
            .fetch();
    }

    /**
     * The site including the table it was classified against, which rule answered, and the table it
     * resolved on: the claim of every case here, and the grain spelled out.
     */
    private static String render(Record row) {
        return row.get(INTENT_INPUT_FIELD_COLUMN_SCOPE.TYPE_NAME) + "."
            + row.get(INTENT_INPUT_FIELD_COLUMN_SCOPE.FIELD_NAME) + "@"
            + row.get(INTENT_INPUT_FIELD_COLUMN_SCOPE.RESOLVING_TABLE) + " "
            + row.get(INTENT_INPUT_FIELD_COLUMN_SCOPE.BASIS) + " "
            + row.get(INTENT_INPUT_FIELD_COLUMN_SCOPE.TABLE_NAME);
    }
}
