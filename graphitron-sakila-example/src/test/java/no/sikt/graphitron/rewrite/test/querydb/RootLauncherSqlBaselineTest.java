package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The root SELECT launcher family's SQL equivalence baseline, extending the programme-level
 * harness ({@code ProjectionSqlBaselineTest}'s {@code SQL_LOG} idiom): whole rendered statements,
 * exact strings and statement counts, one representative query per covered root shape. The
 * launcher reshape's promise is "shape may move, SQL may not": the inline
 * {@code select/from/where/orderBy/fetch} chain moves out of the fetcher body into a named
 * {@code rows<Field>(dsl, env)} launcher unit, and these pins are what makes "the cutover moved
 * no SQL" a result rather than a claim.
 *
 * <p>All strings here were authored against the pre-cutover inline emission and must stay green
 * unchanged through every launcher slice. Editing an expected string during the launcher item is
 * a defect being papered over, not test maintenance; the strings otherwise move only when their
 * fixture types or seed data change.
 *
 * <p>The shapes: a filtered plain list root ({@code films(rating:)}, condition glue in the
 * WHERE), a filterless plain list root ({@code allActors}, proving the neutral condition
 * composed from a missing WHERE slot renders no WHERE clause at all), the single-cardinality
 * plain root ({@code customerByPk}, the {@code fetchOne()} shape with no ORDER BY), the
 * argument-ordered plain list root ({@code filmsOrdered}, the ordering's helper-dispatch arm),
 * and the connection root's page query ({@code filmsConnection}, the seek/limit chain with the
 * cursor key riding the select list), authored with slice 1 per the item's acceptance so the
 * connection cutover inherits a frozen pin.
 */
@ExecutionTier
class RootLauncherSqlBaselineTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final java.util.List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    var sql = ctx.sql();
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(java.util.Locale.ROOT));
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearSqlLog() {
        SQL_LOG.clear();
    }

    @Test
    void filteredPlainListRoot_oneStatementWithGlueWhereAndDefaultOrder() {
        execute("{ films(rating: PG) { title } }");
        assertThat(SQL_LOG)
            .as("plain list root with a live filter: one statement, the condition glue's "
                + "equality in the WHERE, the synthesised PK default order")
            .containsExactly(
                "select \"public\".\"film\".\"title\" "
                    + "from \"public\".\"film\" "
                    + "where \"public\".\"film\".\"rating\" = cast(? as \"public\".\"mpaa_rating\") "
                    + "order by \"public\".\"film\".\"film_id\" asc");
    }

    @Test
    void filterlessPlainListRoot_noWhereClauseAtAll() {
        execute("{ allActors { firstName } }");
        assertThat(SQL_LOG)
            .as("plain list root with no live filters: the coordinate has no condition row, the "
                + "neutral condition composes from that absence, and no WHERE clause renders")
            .containsExactly(
                "select \"public\".\"actor\".\"first_name\" "
                    + "from \"public\".\"actor\" "
                    + "order by \"public\".\"actor\".\"actor_id\" asc");
    }

    @Test
    void singleCardinalityPlainRoot_fetchOneWithNoOrderBy() {
        execute("{ customerByPk(customerId: 1) { lastName } }");
        assertThat(SQL_LOG)
            .as("single-cardinality plain root: the fetchOne() shape, filter in the WHERE, no "
                + "ORDER BY (a scalar lookup is unordered by construction)")
            .containsExactly(
                "select \"public\".\"customer\".\"last_name\" "
                    + "from \"public\".\"customer\" "
                    + "where \"public\".\"customer\".\"customer_id\" = ?");
    }

    @Test
    void argumentOrderedPlainListRoot_helperDispatchedOrderBy() {
        execute("{ filmsOrdered(order: [{field: TITLE, direction: DESC}]) { title } }");
        assertThat(SQL_LOG)
            .as("plain list root with a runtime orderBy argument: the ordering dispatches "
                + "through the emitted filmsOrderedOrderBy helper and lands as the argument's "
                + "column and direction")
            .containsExactly(
                "select \"public\".\"film\".\"title\" "
                    + "from \"public\".\"film\" "
                    + "order by \"public\".\"film\".\"title\" desc");
    }

    @Test
    void connectionRootPageQuery_seekLimitChainWithCursorKeyInSelectList() {
        execute("{ filmsConnection(first: 2) { edges { node { title } } } }");
        assertThat(SQL_LOG)
            .as("connection root, page query only: the cursor key (film_id) rides the select "
                + "list beside the selected column, PK order, limit as bind; pinned with slice 1 "
                + "so the connection cutover inherits a frozen string")
            .containsExactly(
                "select \"public\".\"film\".\"title\", \"public\".\"film\".\"film_id\" "
                    + "from \"public\".\"film\" "
                    + "order by \"public\".\"film\".\"film_id\" asc "
                    + "fetch next ? rows only");
    }

    @Test
    void connectionRootWithTotalCount_pageQueryPlusOneSelectCount() {
        execute("{ filmsConnection(first: 2) { totalCount edges { node { title } } } }");
        assertThat(SQL_LOG)
            .as("connection root with totalCount selected: the lazy resolver issues one "
                + "SELECT count(*) against the same source and predicate the page query ran "
                + "under, two statements total")
            .containsExactlyInAnyOrder(
                "select \"public\".\"film\".\"title\", \"public\".\"film\".\"film_id\" "
                    + "from \"public\".\"film\" "
                    + "order by \"public\".\"film\".\"film_id\" asc "
                    + "fetch next ? rows only",
                "select count(*) from \"public\".\"film\"");
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
