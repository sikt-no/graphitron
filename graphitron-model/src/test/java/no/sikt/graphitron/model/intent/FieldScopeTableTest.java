package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_SCOPE_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SCOPE_TABLE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldSynthesis;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_field_scope_table} resolves: the table a field's own generated SQL is rooted
 * in, which is where a predicate written at the coordinate correlates and where a path departing it
 * starts.
 *
 * <p>The rung cases mirror {@code ArgumentScopeTableTest}'s and assert which rung answered rather
 * than only that a table came out, for the reason that test states: the two rungs reach the same
 * table often enough that a case asserting the table alone would pass with the precedence inverted.
 *
 * <p>Two further sections carry what is this relation's own rather than inherited. One is the grain:
 * a field with no arguments at all resolves a scope here, which is the fact the argument-grain
 * spelling could not hold and the reason this relation exists. The other is the boundary against
 * {@code intent_field_column_scope}, whose name is close enough that a reader could take one for the
 * other; the two disagree on a scalar leaf field, and a case pins the disagreement so neither can be
 * quietly widened into the other.
 */
class FieldScopeTableTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== NAMED_TYPE_TABLE =====

    /** The ordinary case: the field returns a bound type and its statement is rooted in that table. */
    @Test
    void theFieldsNamedTypesOwnTableIsTheScope() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);

            assertThat(rows(dsl).map(FieldScopeTableTest::render))
                .containsExactly("Query.films NAMED_TYPE_TABLE film");
        });
    }

    /**
     * A connection field is rooted in its element type's table rather than its edge wrapper's, which
     * is the reading the authored type expression carries and the reason the rung reads
     * {@code graphitron_field_synthesis} at all.
     */
    @Test
    void aConnectionFieldScopesOnItsElementTypesTable() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedType(dsl, GRAPH, "FilmConnection", "OBJECT");
            seedField(dsl, GRAPH, "Query", "films", "FilmConnection", false);
            seedFieldSynthesis(dsl, GRAPH, "Query", "films", "CONNECTION", "[Film!]!");

            assertThat(rows(dsl).map(FieldScopeTableTest::render))
                .containsExactly("Query.films NAMED_TYPE_TABLE film");
        });
    }

    /**
     * A child field of a bound parent is rooted in its own named type's table and not its parent's,
     * which is what makes this relation the departure of a join rather than a restatement of the
     * parent's binding.
     */
    @Test
    void aChildFieldScopesOnItsOwnNamedTypeAndNotItsParents() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedField(dsl, GRAPH, "Film", "actors", "Actor", true);

            assertThat(rows(dsl).map(FieldScopeTableTest::render))
                .containsExactly("Film.actors NAMED_TYPE_TABLE actor");
        });
    }

    // ===== MUTATION_TABLE =====

    /**
     * A delete surface returns a status type nothing binds, and its statement is still rooted in the
     * table the mutation names. The rung that makes such a coordinate answerable at all.
     */
    @Test
    void aMutationTableAnswersWhereTheReturnTypeBindsNothing() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Boolean", "SCALAR");
            seedField(dsl, GRAPH, "Mutation", "deleteFilm", "Boolean", false);
            seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE", "film");

            assertThat(rows(dsl).map(FieldScopeTableTest::render))
                .containsExactly("Mutation.deleteFilm MUTATION_TABLE film");
        });
    }

    /**
     * Both rungs answer and the named type wins, which is the classifier's own order. Asserted as one
     * row rather than as a preferred one of two: a union here would hand a consumer two tables to
     * emit a predicate on.
     */
    @Test
    void theNamedTypeOutranksTheMutationTable() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "createFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "createFilm", "INSERT", "actor");

            assertThat(rows(dsl).map(FieldScopeTableTest::render))
                .containsExactly("Mutation.createFilm NAMED_TYPE_TABLE film");
        });
    }

    /**
     * An ambiguous binding stops the upper rung and does not stop the lower one, the rungs being a
     * precedence over answers rather than over coordinates.
     */
    @Test
    void anAmbiguousNamedTypeFallsThroughToTheMutationTable() {
        withCatalog(dsl -> {
            seedTwoSchemasNamed(dsl, "venue");
            seedTableBinding(dsl, GRAPH, "Film", "venue");
            seedField(dsl, GRAPH, "Mutation", "createFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "createFilm", "INSERT", "film");

            assertThat(rows(dsl).map(FieldScopeTableTest::render))
                .containsExactly("Mutation.createFilm MUTATION_TABLE film");
        });
    }

    // ===== Where certainty is refused =====

    /**
     * Two candidate tables are two different predicates, so an ambiguously bound named type is no
     * scope rather than a picked one. The lower rung is not consulted either: this field carries no
     * {@code @mutation}, and the case that shows the fall-through is the one above.
     */
    @Test
    void anAmbiguouslyBoundNamedTypeIsNoScopeAtAll() {
        withCatalog(dsl -> {
            seedTwoSchemasNamed(dsl, "venue");
            seedTableBinding(dsl, GRAPH, "Film", "venue");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A field returning a type nothing binds has no scope, which is the ordinary answer for every
     * field that reads no table at all.
     */
    @Test
    void aFieldReturningAnUnboundTypeHasNoScope() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Report", "OBJECT");
            seedField(dsl, GRAPH, "Query", "report", "Report", false);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's coordinates read none of this one's scope. */
    @Test
    void aSiblingGraphReadsNoneOfTheScope() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== The grain, which is what this relation is for =====

    /**
     * A field with no arguments resolves a scope. The whole reason the rule is stated at this grain:
     * the argument-grain spelling could answer only where an argument existed to carry the answer,
     * and an authored {@code @condition} on a field with no arguments filters a table nothing there
     * could name.
     */
    @Test
    void aFieldWithNoArgumentsStillResolvesAScope() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);

            assertThat(rows(dsl).map(FieldScopeTableTest::render))
                .containsExactly("Query.films NAMED_TYPE_TABLE film");
            assertThat(argumentRows(dsl)).isEmpty();
        });
    }

    /**
     * The argument-grain relation is this one fanned over the field's arguments and nothing else:
     * one row per argument, all carrying the same rung and the same table. Pinned here rather than
     * only in the argument relation's own test, because the fan-out is now that relation's whole
     * content and a drift between the two would be this relation's to answer for.
     */
    @Test
    void theArgumentGrainRelationIsThisOneFannedOverTheArguments() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "inActor", "ID");
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");

            assertThat(rows(dsl).map(FieldScopeTableTest::render))
                .containsExactly("Query.films NAMED_TYPE_TABLE film");
            assertThat(argumentRows(dsl)).containsExactly(
                "Query.films(inActor) NAMED_TYPE_TABLE film",
                "Query.films(title) NAMED_TYPE_TABLE film");
        });
    }

    // ===== The boundary against the column scope =====

    /**
     * A scalar leaf field of a bound parent has no row here, where {@code intent_field_column_scope}
     * answers it with the parent's binding. The two relations part company exactly there, and the
     * difference is the question: a column name written at this site resolves against the parent's
     * table, while the field's own statement is not rooted in a table at all, a scalar field being a
     * projection off its parent's row rather than a read of its own.
     */
    @Test
    void aScalarLeafFieldIsNotScopedByItsParentsBinding() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Film", "title", "String", false);

            assertThat(rows(dsl)).isEmpty();
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
            }
            seedType(dsl, GRAPH, "ID", "SCALAR");
            body.accept(dsl);
        });
    }

    /**
     * One table name declared in two schemas, which is how a spelling resolves to two tables and a
     * binding over it becomes ambiguous. Stated as a catalog shape rather than as two bindings on one
     * type, the store keying a written {@code @table} by graph and type and admitting only one.
     */
    private static void seedTwoSchemasNamed(DSLContext dsl, String tableName) {
        for (String schema : List.of(PUBLIC, "archive")) {
            seedTable(dsl, PKG, schema, tableName);
            seedConstraint(dsl, PKG, schema, tableName, tableName + "_pkey", "PRIMARY KEY", null);
        }
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_SCOPE_TABLE.fields())
            .from(INTENT_FIELD_SCOPE_TABLE)
            .where(INTENT_FIELD_SCOPE_TABLE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_FIELD_SCOPE_TABLE.TYPE_NAME,
                INTENT_FIELD_SCOPE_TABLE.FIELD_NAME,
                INTENT_FIELD_SCOPE_TABLE.TABLE_NAME)
            .fetch();
    }

    /** The argument-grain fan-out, rendered the way its own test renders it. */
    private static List<String> argumentRows(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_ARGUMENT_SCOPE_TABLE.fields())
            .from(INTENT_ARGUMENT_SCOPE_TABLE)
            .where(INTENT_ARGUMENT_SCOPE_TABLE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_ARGUMENT_SCOPE_TABLE.TYPE_NAME,
                INTENT_ARGUMENT_SCOPE_TABLE.FIELD_NAME,
                INTENT_ARGUMENT_SCOPE_TABLE.ARGUMENT_NAME)
            .fetch()
            .map(row -> row.get(INTENT_ARGUMENT_SCOPE_TABLE.TYPE_NAME) + "."
                + row.get(INTENT_ARGUMENT_SCOPE_TABLE.FIELD_NAME) + "("
                + row.get(INTENT_ARGUMENT_SCOPE_TABLE.ARGUMENT_NAME) + ") "
                + row.get(INTENT_ARGUMENT_SCOPE_TABLE.BASIS) + " "
                + row.get(INTENT_ARGUMENT_SCOPE_TABLE.TABLE_NAME));
    }

    /** The coordinate, which rung answered, and the table it reached: the claim of every case here. */
    private static String render(Record row) {
        return row.get(INTENT_FIELD_SCOPE_TABLE.TYPE_NAME) + "."
            + row.get(INTENT_FIELD_SCOPE_TABLE.FIELD_NAME) + " "
            + row.get(INTENT_FIELD_SCOPE_TABLE.BASIS) + " "
            + row.get(INTENT_FIELD_SCOPE_TABLE.TABLE_NAME);
    }
}
