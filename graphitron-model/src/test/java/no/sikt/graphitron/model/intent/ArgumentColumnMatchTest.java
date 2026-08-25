package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_COLUMN_MATCH;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUnionMember;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_argument_column_match} returns: which column an argument's own name resolves to
 * on the table its site navigates to, which is the column a filter predicate built from it compares
 * against.
 *
 * <p>The reading is the field-site classifier's transcribed to this site, so the cases that carry
 * weight are the ones where the two sites are not the same question. The gate on the argument's
 * named type is one: an input-object argument names a column often enough by accident that a
 * spurious match here would be a row a consumer acted on. The navigation is the other: an argument's
 * names resolve where its own content binds and not where its parent's row sits, so a case seeds a
 * path and asserts the column comes off the terminal table rather than off the field's own.
 *
 * <p>Every input is stated as rows, for the reason the field-site test states: the two-tier name
 * match only shows its precedence where a column's generated Java name and its SQL name disagree,
 * and a catalog generated from real DDL has them agree everywhere. Those are catalog states, and a
 * fixture reaching them through a generator is choosing its inputs by what the generator happens to
 * emit.
 */
class ArgumentColumnMatchTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== The resolved row =====

    /**
     * The projection: the name that resolved and the tier that resolved it, beside the witness naming
     * the {@code sql_column} key it landed on, and the argument's own declaration position carried
     * through so a diagnostic reading this row needs no second join.
     */
    @Test
    void theRowNamesTheMatchItsTierAndItsWitness() {
        withCatalog(dsl -> {
            seedFilmQuery(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");

            var rows = rows(dsl);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.TYPE_NAME)).isEqualTo("Query");
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.FIELD_NAME)).isEqualTo("films");
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.ARGUMENT_NAME)).isEqualTo("title");
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.MATCHED_NAME)).isEqualTo("title");
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.MATCHED_BY)).isEqualTo("JOOQ_NAME");
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.TABLE_SOURCE_NAME)).isEqualTo(PKG);
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.TABLE_SCHEMA)).isEqualTo(PUBLIC);
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.TABLE_NAME)).isEqualTo("film");
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.COLUMN_NAME)).isEqualTo("title");

            var argument = dsl.selectFrom(GRAPHQL_ARGUMENT)
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(GRAPH))
                .and(GRAPHQL_ARGUMENT.TYPE_NAME.eq("Query"))
                .and(GRAPHQL_ARGUMENT.FIELD_NAME.eq("films"))
                .and(GRAPHQL_ARGUMENT.ARGUMENT_NAME.eq("title")).fetchSingle();
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.SOURCE_NAME))
                .isEqualTo(argument.getSourceName());
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.SOURCE_LINE))
                .as("the argument's own declaration position, which is where a diagnostic about an"
                    + " unresolvable filter name points")
                .isEqualTo(argument.getSourceLine());
            assertThat(row.get(INTENT_ARGUMENT_COLUMN_MATCH.SOURCE_COLUMN))
                .isEqualTo(argument.getSourceColumn());
        });
    }

    /**
     * A written {@code @field(name:)} is the effective name and the argument's own is not consulted,
     * which is what lets an author filter on a column the argument is not named after.
     */
    @Test
    void anArgumentBindingIsTheEffectiveNameWhereOneIsWritten() {
        withCatalog(dsl -> {
            seedFilmQuery(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "year", "String");
            seedArgumentBinding(dsl, GRAPH, "Query", "films", "year", "release_year");

            assertThat(rows(dsl).map(ArgumentColumnMatchTest::render))
                .containsExactly("Query.films(year) release_year=film.release_year JOOQ_NAME");
        });
    }

    // ===== The two tiers =====

    /**
     * The generated Java name is tried before the SQL name, and the case only says so where the two
     * disagree: one column carries the argument's name as its SQL name and another carries it as its
     * generated name, and the second wins.
     */
    @Test
    void theGeneratedNameTierMatchesBeforeTheSqlName() {
        withCatalog(dsl -> {
            seedFilmQuery(dsl);
            seedColumn(dsl, PKG, PUBLIC, "film", "tiered", 8, "X_TIERED");
            seedColumn(dsl, PKG, PUBLIC, "film", "x_tiered", 9, "TIERED");
            seedArgument(dsl, GRAPH, "Query", "films", "tiered", "String");

            assertThat(rows(dsl).map(ArgumentColumnMatchTest::render))
                .containsExactly("Query.films(tiered) tiered=film.x_tiered JOOQ_NAME");
        });
    }

    /**
     * Both tiers fold case, so a column whose SQL name is spelled differently from the argument still
     * resolves and says which tier answered. The losing tier is where a name lands when no generated
     * name matches it, which is the ordinary shape of a catalog whose Java names are upper-cased.
     */
    @Test
    void theSqlNameTierAnswersWhenNoGeneratedNameDoes() {
        withCatalog(dsl -> {
            seedFilmQuery(dsl);
            seedColumn(dsl, PKG, PUBLIC, "film", "Rating", 8, "X_RATING");
            seedArgument(dsl, GRAPH, "Query", "films", "rating", "String");

            assertThat(rows(dsl).map(ArgumentColumnMatchTest::render))
                .containsExactly("Query.films(rating) rating=film.Rating SQL_NAME");
        });
    }

    // ===== The navigation =====

    /**
     * An argument's names resolve where its own content binds, and a written path moves that. The
     * column comes off the path's terminal table even though the field's own table carries a column
     * of the same name, which is the reading that would be wrong if this view read the scope's lower
     * rung directly.
     */
    @Test
    void aWrittenPathResolvesTheNameOnItsTerminalTable() {
        withCatalog(dsl -> {
            seedFilmQuery(dsl);
            seedColumn(dsl, PKG, PUBLIC, "film", "name", 8, "NAME");
            seedColumn(dsl, PKG, PUBLIC, "actor", "name", 2, "NAME");
            seedArgument(dsl, GRAPH, "Query", "films", "name", "String");
            seedArgumentReference(dsl, GRAPH, "Query", "films", "name", 0);
            seedArgumentReferenceStep(dsl, GRAPH, "Query", "films", "name", 0, 0,
                "film_actor", null);
            seedArgumentReferenceStep(dsl, GRAPH, "Query", "films", "name", 0, 1, "actor", null);

            assertThat(rows(dsl).map(ArgumentColumnMatchTest::render))
                .containsExactly("Query.films(name) name=actor.name JOOQ_NAME");
        });
    }

    /**
     * A site the scope relation declines is a silence here rather than a fall-back, and this view adds
     * no decline of its own. A repeated {@code @reference} is the scope's own conflict, and the column
     * the argument names exists on the field's table, so a fall-back would have produced a row.
     */
    @Test
    void aSiteTheScopeDeclinesResolvesToNothing() {
        withCatalog(dsl -> {
            seedFilmQuery(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgumentReference(dsl, GRAPH, "Query", "films", "title", 0);
            seedArgumentReferenceStep(dsl, GRAPH, "Query", "films", "title", 0, 0,
                "film_actor", null);
            seedArgumentReference(dsl, GRAPH, "Query", "films", "title", 1);
            seedArgumentReferenceStep(dsl, GRAPH, "Query", "films", "title", 1, 0, "actor", null);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== Where nothing resolves =====

    /**
     * An argument whose named type is an input object is not column-shaped, and its fields resolve at
     * their own sites. The name is one the film table carries, so the gate is what keeps this row out
     * rather than the absence of a candidate column.
     */
    @Test
    void anInputObjectArgumentResolvesToNothingEvenWhereItsNameIsAColumn() {
        withCatalog(dsl -> {
            seedFilmQuery(dsl);
            seedType(dsl, GRAPH, "FilmFilter", "INPUT_OBJECT");
            seedArgument(dsl, GRAPH, "Query", "films", "title", "FilmFilter");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A name matching no column on the resolved table resolves to nothing, which is the resolver's own
     * unbound-argument rejection and is equally the ordinary answer for an argument whose content is
     * not column-shaped at all. Nothing here tells those two apart.
     */
    @Test
    void aNameMatchingNoColumnResolvesToNothing() {
        withCatalog(dsl -> {
            seedFilmQuery(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "first", "String");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's arguments resolve none of these columns. */
    @Test
    void aSiblingGraphResolvesNoneOfTheseColumns() {
        withCatalog(dsl -> {
            seedFilmQuery(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== The branches of a polymorphic root =====

    /**
     * An argument of a field returning a multi-table polymorphic container resolves one column per
     * branch, each on that branch's own table. The site here is the argument together with the table,
     * which is what the scope this reads keys on, and it is what the classifier does: it lowers the
     * filter surface once per participant so the generated predicate compares a column of the table
     * that branch selects from.
     */
    @Test
    void anArgumentOfAPolymorphicRootResolvesAColumnOnEachBranch() {
        withCatalog(dsl -> {
            seedColumn(dsl, PKG, PUBLIC, "film", "name", 3, "NAME");
            seedColumn(dsl, PKG, PUBLIC, "actor", "name", 2, "NAME");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedUnionMember(dsl, GRAPH, "Document", "Actor", 2);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);
            seedArgument(dsl, GRAPH, "Query", "documents", "name", "String");

            assertThat(rows(dsl).map(ArgumentColumnMatchTest::render)).containsExactly(
                "Query.documents(name) name=actor.name JOOQ_NAME",
                "Query.documents(name) name=film.name JOOQ_NAME");
        });
    }

    /**
     * A name reaching a column on one branch and none on another resolves on the branch that has it
     * and stays silent on the branch that does not. That silence is the classifier's own
     * per-participant rejection: it lowers each branch and fails on the one whose table has no such
     * column, so the build never emits this coordinate. The relation states the resolution and the
     * divergence is a detection over it, one branch here against the two the coordinate is rooted in,
     * rather than a verdict this view reaches on its own.
     */
    @Test
    void aNameOnOneBranchOnlyResolvesThereAndIsSilentOnTheOther() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedUnionMember(dsl, GRAPH, "Document", "Actor", 2);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);
            seedArgument(dsl, GRAPH, "Query", "documents", "title", "String");

            assertThat(rows(dsl).map(ArgumentColumnMatchTest::render))
                .containsExactly("Query.documents(title) title=film.title JOOQ_NAME");
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor", "film_actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            foreignKey(dsl, "film_actor", "film_actor_film_id_fkey", "film");
            foreignKey(dsl, "film_actor", "film_actor_actor_id_fkey", "actor");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "film", "release_year", 2, "RELEASE_YEAR");
            seedColumn(dsl, PKG, PUBLIC, "actor", "last_name", 1, "LAST_NAME");
            seedType(dsl, GRAPH, "String", "SCALAR");
            body.accept(dsl);
        });
    }

    /** One foreign key from {@code table} to {@code referencedTable}'s primary key. */
    private static void foreignKey(DSLContext dsl, String table, String constraintName,
                                   String referencedTable) {
        seedConstraint(dsl, PKG, PUBLIC, table, constraintName, "FOREIGN KEY", null);
        seedReferentialConstraint(dsl, PKG, PUBLIC, table, constraintName,
            PKG, PUBLIC, referencedTable, referencedTable + "_pkey");
    }

    /** {@code Query.films: [Film!]!} with {@code Film} bound to the film table, the site every case sits on. */
    private static void seedFilmQuery(DSLContext dsl) {
        seedTableBinding(dsl, GRAPH, "Film", "film");
        seedField(dsl, GRAPH, "Query", "films", "Film", true);
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_ARGUMENT_COLUMN_MATCH.fields())
            .from(INTENT_ARGUMENT_COLUMN_MATCH)
            .where(INTENT_ARGUMENT_COLUMN_MATCH.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_ARGUMENT_COLUMN_MATCH.TYPE_NAME,
                INTENT_ARGUMENT_COLUMN_MATCH.FIELD_NAME,
                INTENT_ARGUMENT_COLUMN_MATCH.ARGUMENT_NAME,
                INTENT_ARGUMENT_COLUMN_MATCH.TABLE_NAME)
            .fetch();
    }

    /** The site, the name that resolved, the column it landed on, and which tier answered. */
    private static String render(Record row) {
        return row.get(INTENT_ARGUMENT_COLUMN_MATCH.TYPE_NAME) + "."
            + row.get(INTENT_ARGUMENT_COLUMN_MATCH.FIELD_NAME) + "("
            + row.get(INTENT_ARGUMENT_COLUMN_MATCH.ARGUMENT_NAME) + ") "
            + row.get(INTENT_ARGUMENT_COLUMN_MATCH.MATCHED_NAME) + "="
            + row.get(INTENT_ARGUMENT_COLUMN_MATCH.TABLE_NAME) + "."
            + row.get(INTENT_ARGUMENT_COLUMN_MATCH.COLUMN_NAME) + " "
            + row.get(INTENT_ARGUMENT_COLUMN_MATCH.MATCHED_BY);
    }
}
