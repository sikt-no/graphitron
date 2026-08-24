package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_COLUMN_SCOPE;
import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_argument_column_scope} resolves: the table a column name written at an
 * argument's site resolves against, which is the table a filter predicate built from that argument
 * compares on.
 *
 * <p>The cases are organised by rule and every one of them asserts which rule answered rather than
 * only that a table came out. The two rules reach the same table at most sites, so a case asserting
 * the table alone would pass with the rules swapped, and which rule answered is what a consumer
 * takes: it is also the fork between a predicate on the table the field already selects from and one
 * a join away.
 *
 * <p>Where this relation declines, the decline gets a case of its own stating the absence rather
 * than being left to be inferred from a passing neighbour. Two of those declines are this site's
 * own rather than inherited from the field-site twin, and they are the reason this is a separate
 * relation and not that view with a column added: a repeated {@code @reference} composes a chain on
 * a field and is a conflict on an argument, so a site carrying two applications resolves to nothing
 * here where the field-site rule would take the first.
 */
class ArgumentColumnScopeTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== ARGUMENT_SCOPE =====

    /**
     * The ordinary case: the argument writes no path, so its names resolve on the table its own
     * content binds against, which is the field's named type's table.
     */
    @Test
    void anArgumentWithNoPathResolvesOnItsOwnScopeTable() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");

            assertThat(rows(dsl).map(ArgumentColumnScopeTest::render))
                .containsExactly("Query.films(title) ARGUMENT_SCOPE film");
        });
    }

    /**
     * An element-less {@code @reference(path: [])} is legal SDL and inert, and this relation reads it
     * the way the resolver does. The anti-join is on the path's elements and never on the directive's
     * presence, so such a site takes the scope rule and resolves where a directive-less argument
     * would have.
     */
    @Test
    void anElementLessReferenceIsInertAndLeavesTheScopeRuleStanding() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgumentReference(dsl, GRAPH, "Query", "films", "title", 0);

            assertThat(rows(dsl).map(ArgumentColumnScopeTest::render))
                .containsExactly("Query.films(title) ARGUMENT_SCOPE film");
        });
    }

    /** Every argument of one field is its own row, so no reader has to know that they agree. */
    @Test
    void everyArgumentOfOneFieldIsItsOwnRow() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgument(dsl, GRAPH, "Query", "films", "rating", "String");

            assertThat(rows(dsl).map(ArgumentColumnScopeTest::render)).containsExactly(
                "Query.films(rating) ARGUMENT_SCOPE film",
                "Query.films(title) ARGUMENT_SCOPE film");
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
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedTablePath(dsl, "Query", "films", "inActor", "film_actor", "actor");

            assertThat(rows(dsl).map(ArgumentColumnScopeTest::render))
                .containsExactly("Query.films(inActor) PATH_TERMINAL actor");
        });
    }

    /**
     * A path is one rule and the scope is the other, and they are disjoint rather than ranked: a
     * site whose path resolves gets the terminal alone, never the terminal beside the table its
     * content binds against. Disjointness is what lets this relation be a plain union with no
     * windowed collapse over it, so the property is asserted as an arity here rather than assumed.
     */
    @Test
    void aResolvedPathIsTheSitesOnlyRow() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedTablePath(dsl, "Query", "films", "inActor", "film_actor");

            var rows = rows(dsl);
            assertThat(rows).hasSize(1);
            assertThat(render(rows.getFirst()))
                .isEqualTo("Query.films(inActor) PATH_TERMINAL film_actor");
        });
    }

    /**
     * {@code film} declares two foreign keys to {@code language}, so the element reaches one
     * destination by two routes. The rule demands a single destination and not a single route, so
     * this resolves, and it resolves once: the arm keeps only the table and takes it distinct, which
     * is what makes demanding the destination safe.
     */
    @Test
    void anElementReachingOneTableByTwoRoutesIsOneRow() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedTablePath(dsl, "Query", "films", "inLanguage", "language");

            assertThat(rows(dsl).map(ArgumentColumnScopeTest::render))
                .containsExactly("Query.films(inLanguage) PATH_TERMINAL language");
        });
    }

    // ===== Where certainty is refused =====

    /**
     * A spelling two schemas both declare reaches two destinations, and two destinations are two
     * different predicates. The row is absent rather than picked, and the case asserts the absence
     * whole: a path that reaches nowhere certain must not fall back on the argument's own scope,
     * because resolving a name there points the author at the wrong end of a join.
     */
    @Test
    void aTerminalReachingTwoTablesResolvesNowhereAndDoesNotFallBack() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedTablePath(dsl, "Query", "films", "inVenue", "venue");

            assertThat(rows(dsl)).isEmpty();
            assertThat(dsl.select(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.TARGETS)
                    .from(INTENT_ARGUMENT_REFERENCE_STEP_TARGET)
                    .where(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.GRAPH_NAME.eq(GRAPH))
                    .fetch(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.TARGETS))
                .as("the premise of the absence above: the element resolved, and it resolved to two"
                    + " destinations. Without this the case would pass just as well on a path that"
                    + " reached nothing at all, which is a different rule declining")
                .containsExactly(2, 2);
        });
    }

    /**
     * Repetition is where the two sites genuinely differ. A repeated {@code @reference} on a field
     * composes an ordered chain and the field-site rule takes the first application; on an argument
     * order composition has no meaning, so the resolver rejects the repetition outright and there is
     * no first application for this relation to prefer. The absence is the repetition's and not the
     * path's: the case above resolves this same first application on its own.
     */
    @Test
    void aRepeatedReferenceIsAConflictRatherThanAChain() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedTablePath(dsl, "Query", "films", "inActor", "film_actor");
            seedApplication(dsl, "Query", "films", "inActor", 1, "language");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * The count that decides the conflict is over the applications and not over their elements, so
     * an empty one written beside a real one is the same conflict. Counting elements would let this
     * site through as though one directive had been written, which is not what the author wrote and
     * not what the resolver reads.
     */
    @Test
    void anEmptyApplicationBesideARealOneIsTheSameConflict() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "inActor", "String");
            seedArgumentReference(dsl, GRAPH, "Query", "films", "inActor", 0);
            seedApplication(dsl, "Query", "films", "inActor", 1, "film_actor");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A field returning a type nothing binds gives its arguments no scope, so nothing resolves at
     * their sites either. The decline is inherited rather than restated, which is the whole point of
     * reading the scope relation instead of spelling its rungs again.
     */
    @Test
    void aFieldReturningAnUnboundTypeResolvesNoneOfItsArguments() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Report", "OBJECT");
            seedField(dsl, GRAPH, "Query", "report", "Report", false);
            seedArgument(dsl, GRAPH, "Query", "report", "since", "String");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's coordinates resolve none of this one's sites. */
    @Test
    void aSiblingGraphResolvesNoneOfTheseSites() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");

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

    /** An argument carrying one {@code @reference} whose elements each spell a table. */
    private static void seedTablePath(DSLContext dsl, String typeName, String fieldName,
                                      String argumentName, String... tableRefs) {
        seedArgument(dsl, GRAPH, typeName, fieldName, argumentName, "String");
        seedApplication(dsl, typeName, fieldName, argumentName, 0, tableRefs);
    }

    /** One more {@code @reference} application on an argument that already carries one. */
    private static void seedApplication(DSLContext dsl, String typeName, String fieldName,
                                        String argumentName, int ordinal, String... tableRefs) {
        seedArgumentReference(dsl, GRAPH, typeName, fieldName, argumentName, ordinal);
        for (int position = 0; position < tableRefs.length; position++) {
            seedArgumentReferenceStep(dsl, GRAPH, typeName, fieldName, argumentName, ordinal,
                position, tableRefs[position], null);
        }
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_ARGUMENT_COLUMN_SCOPE.fields())
            .from(INTENT_ARGUMENT_COLUMN_SCOPE)
            .where(INTENT_ARGUMENT_COLUMN_SCOPE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_ARGUMENT_COLUMN_SCOPE.TYPE_NAME,
                INTENT_ARGUMENT_COLUMN_SCOPE.FIELD_NAME,
                INTENT_ARGUMENT_COLUMN_SCOPE.ARGUMENT_NAME,
                INTENT_ARGUMENT_COLUMN_SCOPE.TABLE_SCHEMA,
                INTENT_ARGUMENT_COLUMN_SCOPE.TABLE_NAME)
            .fetch();
    }

    /** The site, which rule answered, and the table it resolved on: the claim of every case here. */
    private static String render(Record row) {
        return row.get(INTENT_ARGUMENT_COLUMN_SCOPE.TYPE_NAME) + "."
            + row.get(INTENT_ARGUMENT_COLUMN_SCOPE.FIELD_NAME) + "("
            + row.get(INTENT_ARGUMENT_COLUMN_SCOPE.ARGUMENT_NAME) + ") "
            + row.get(INTENT_ARGUMENT_COLUMN_SCOPE.BASIS) + " "
            + row.get(INTENT_ARGUMENT_COLUMN_SCOPE.TABLE_NAME);
    }
}
