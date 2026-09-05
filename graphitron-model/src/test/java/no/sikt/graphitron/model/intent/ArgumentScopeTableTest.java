package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_SCOPE_TABLE;
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
 * What {@code intent_argument_scope_table} resolves: the table an argument's column-shaped content
 * binds against, which is where a predicate built from it correlates and where a path departing it
 * starts.
 *
 * <p>The cases are organised by rung, and the assertion is which rung answered rather than only that
 * a table came out. That is the claim worth pinning: the two rungs reach the same table often enough
 * that a case asserting the table alone would pass with the precedence inverted, and the precedence is
 * what a consumer inherits. So the shapes where the rungs compete get cases of their own, and the two
 * shapes where certainty is refused, an ambiguous binding and no binding at all, get cases stating the
 * absence rather than leaving it to be inferred from a passing neighbour.
 */
class ArgumentScopeTableTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== NAMED_TYPE_TABLE =====

    /** The ordinary case: the field returns a bound type and its arguments bind against its table. */
    @Test
    void theFieldsNamedTypesOwnTableIsTheScope() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "inActor", "ID");

            assertThat(rows(dsl).map(ArgumentScopeTableTest::render))
                .containsExactly("Query.films(inActor) NAMED_TYPE_TABLE film");
        });
    }

    /**
     * A connection field binds against its element type's table rather than its edge wrapper's,
     * which is the reading the authored type expression carries and the reason the rung reads
     * {@code graphitron_field_synthesis} at all.
     */
    @Test
    void aConnectionFieldScopesOnItsElementTypesTable() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedType(dsl, GRAPH, "FilmConnection", "OBJECT");
            seedField(dsl, GRAPH, "Query", "films", "FilmConnection", false);
            seedFieldSynthesis(dsl, GRAPH, "Query", "films", "[Film!]!");
            seedArgument(dsl, GRAPH, "Query", "films", "inActor", "ID");

            assertThat(rows(dsl).map(ArgumentScopeTableTest::render))
                .containsExactly("Query.films(inActor) NAMED_TYPE_TABLE film");
        });
    }

    /** Every argument of one field is its own row, so no reader has to know that they agree. */
    @Test
    void everyArgumentOfOneFieldIsItsOwnRow() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "inActor", "ID");
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");

            assertThat(rows(dsl).map(ArgumentScopeTableTest::render)).containsExactly(
                "Query.films(inActor) NAMED_TYPE_TABLE film",
                "Query.films(title) NAMED_TYPE_TABLE film");
        });
    }

    // ===== MUTATION_TABLE =====

    /**
     * A delete surface returns a status type nothing binds, and its arguments still bind against the
     * table the mutation names. The rung that makes such a coordinate answerable at all.
     */
    @Test
    void aMutationTableAnswersWhereTheReturnTypeBindsNothing() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Boolean", "SCALAR");
            seedField(dsl, GRAPH, "Mutation", "deleteFilm", "Boolean", false);
            seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE", "film");
            seedArgument(dsl, GRAPH, "Mutation", "deleteFilm", "id", "ID");

            assertThat(rows(dsl).map(ArgumentScopeTableTest::render))
                .containsExactly("Mutation.deleteFilm(id) MUTATION_TABLE film");
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
            seedArgument(dsl, GRAPH, "Mutation", "createFilm", "in", "FilmInput");

            assertThat(rows(dsl).map(ArgumentScopeTableTest::render))
                .containsExactly("Mutation.createFilm(in) NAMED_TYPE_TABLE film");
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
            seedArgument(dsl, GRAPH, "Query", "films", "inActor", "ID");

            assertThat(rows(dsl)).isEmpty();
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
            seedArgument(dsl, GRAPH, "Mutation", "createFilm", "in", "FilmInput");

            assertThat(rows(dsl).map(ArgumentScopeTableTest::render))
                .containsExactly("Mutation.createFilm(in) MUTATION_TABLE film");
        });
    }

    /**
     * A field returning a type nothing binds has no scope, which is the ordinary answer for every
     * argument whose content is not column-shaped at all.
     */
    @Test
    void aFieldReturningAnUnboundTypeHasNoScope() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Report", "OBJECT");
            seedField(dsl, GRAPH, "Query", "report", "Report", false);
            seedArgument(dsl, GRAPH, "Query", "report", "since", "String");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's coordinates read none of this one's scope. */
    @Test
    void aSiblingGraphReadsNoneOfTheScope() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "inActor", "ID");

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
        return dsl.select(INTENT_ARGUMENT_SCOPE_TABLE.fields())
            .from(INTENT_ARGUMENT_SCOPE_TABLE)
            .where(INTENT_ARGUMENT_SCOPE_TABLE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_ARGUMENT_SCOPE_TABLE.TYPE_NAME,
                INTENT_ARGUMENT_SCOPE_TABLE.FIELD_NAME,
                INTENT_ARGUMENT_SCOPE_TABLE.ARGUMENT_NAME,
                INTENT_ARGUMENT_SCOPE_TABLE.TABLE_NAME)
            .fetch();
    }

    /** The coordinate, which rung answered, and the table it reached: the claim of every case here. */
    private static String render(Record row) {
        return row.get(INTENT_ARGUMENT_SCOPE_TABLE.TYPE_NAME) + "."
            + row.get(INTENT_ARGUMENT_SCOPE_TABLE.FIELD_NAME) + "("
            + row.get(INTENT_ARGUMENT_SCOPE_TABLE.ARGUMENT_NAME) + ") "
            + row.get(INTENT_ARGUMENT_SCOPE_TABLE.BASIS) + " "
            + row.get(INTENT_ARGUMENT_SCOPE_TABLE.TABLE_NAME);
    }
}
