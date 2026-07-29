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
 * The projection SQL baseline: whole rendered statements, frozen. Each test pins the complete
 * {@code SQL_LOG} of one representative projection shape, so both the statement count and every
 * select list are exact, not {@code contains} probes.
 *
 * <p>This deliberately breaks the module's substring-assertion habit, and it is not a carve-out
 * from the ban on code-string assertions: exact rendered SQL at the execution tier is observable
 * behaviour against PostgreSQL, not generator implementation. SQL is the contract with the
 * database; generated Java text is not a contract with anyone, which is the line between this
 * baseline and the byte-identical-output acceptance test the programme refuses.
 *
 * <p>The rule these pins carry: the projection-command reshape kept every string here green
 * unchanged (that is what made "the reshape moved no SQL" a result rather than a claim), and
 * the slice that ended over-projection spent this class's one sanctioned re-baseline: every
 * projection is selection-gated now, so the correlation-key columns of unselected children are
 * gone from these select lists, outer and multiset-inner alike. The strings henceforth move
 * only when their own fixture types or seed data change in the shared {@code schema.graphqls} /
 * {@code init.sql}, and such a re-pin must preserve what the shape demonstrates. Any other edit
 * is a defect being papered over, not test maintenance.
 *
 * <p>The shapes are chosen to cover projection mechanics rather than one emit family: a split
 * child ({@code SplitParent.tags}), a nesting type ({@code Film.summary}), one nesting type
 * shared by two hosts with different tables ({@code OccupantLocation} under {@code Customer}
 * and {@code Store}, whose {@code address} join is inferred per parent, so the pin proves the
 * two anchors' joins differ and not merely their aliases), a multiset child
 * ({@code Customer.address}), a polymorphic root ({@code Query.search}), the over-projection
 * probe (one scalar selected on {@code Customer}, a type with six child fields; the select list
 * carries that scalar and nothing else), and the all-unselected probe (a selection projecting
 * no column at all, answered by the one-field row-present sentinel rather than jOOQ's implicit
 * project-everything).
 *
 * <p>Mechanics: statements are captured at {@code executeStart} with bind placeholders and
 * lowercased (the module convention). Multi-statement pins are order-insensitive, since
 * DataLoader dispatch order is not the claim; count and content are. The polymorphic stage-2
 * statements embed one VALUES row per stage-1 hit, so their arity rides the seed data.
 */
@ExecutionTier
class ProjectionSqlBaselineTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final java.util.List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void startDatabase() throws Exception {
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
    void splitChild_parentStatementAndBatchedChildStatement() {
        execute("{ splitParents { label tags { tag } } }");
        assertThat(SQL_LOG)
            .as("split child: the parent SELECT carries the child's correlation key (parent_code), "
                + "the child runs once as a VALUES-joined batch")
            .containsExactlyInAnyOrder(
                "select \"public\".\"split_parent\".\"label\", \"public\".\"split_parent\".\"parent_code\" "
                    + "from \"public\".\"split_parent\" order by \"public\".\"split_parent\".\"parent_id\" asc",
                "select \"tags_s0\".\"tag\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?), (1, ?)) as \"parentinput\" (\"idx\", \"parent_code\") "
                    + "join \"public\".\"split_parent_tag\" as \"tags_s0\" "
                    + "on \"tags_s0\".\"parent_code\" = \"parentinput\".\"parent_code\"");
    }

    @Test
    void nestingType_projectsAgainstTheHostTable() {
        execute("{ films { summary { title releaseYear } } }");
        assertThat(SQL_LOG)
            .as("nesting type: FilmSummary's fields resolve against the outer film table in the "
                + "host's own statement")
            .containsExactly(
                "select \"public\".\"film\".\"title\", \"public\".\"film\".\"release_year\" "
                    + "from \"public\".\"film\" "
                    + "order by \"public\".\"film\".\"film_id\" asc");
    }

    @Test
    void sharedNestingType_projectsPerHostTable() {
        execute("{ customers(active: true) { location { address { district } } } }");
        var customerStatements = java.util.List.copyOf(SQL_LOG);

        SQL_LOG.clear();
        execute("{ stores { nodes { location { address { district } } } } }");
        var storeStatements = java.util.List.copyOf(SQL_LOG);

        assertThat(customerStatements)
            .as("OccupantLocation under Customer: the shared nesting type's address multiset "
                + "correlates against customer.address_id")
            .containsExactly(
                "select (select coalesce(jsonb_agg(jsonb_build_array(t.\"v0\")), jsonb_build_array()) "
                    + "from (select \"customer_a0\".\"district\" as \"v0\" "
                    + "from \"public\".\"address\" as \"customer_a0\" "
                    + "where \"customer_a0\".\"address_id\" = \"public\".\"customer\".\"address_id\" "
                    + "fetch next ? rows only) as t) as \"__rk_address\" "
                    + "from \"public\".\"customer\" where \"public\".\"customer\".\"activebool\" = ? "
                    + "order by \"public\".\"customer\".\"customer_id\" asc");
        assertThat(storeStatements)
            .as("the same OccupantLocation under Store: the identical nesting type correlates "
                + "against store.address_id, so the shared unit projects per host table; the "
                + "trailing store_id is the connection launcher's cursor key (its extraFields "
                + "slot), selection-independent by design and not a projection-unit column")
            .containsExactly(
                "select (select coalesce(jsonb_agg(jsonb_build_array(t.\"v0\")), jsonb_build_array()) "
                    + "from (select \"store_a0\".\"district\" as \"v0\" "
                    + "from \"public\".\"address\" as \"store_a0\" "
                    + "where \"store_a0\".\"address_id\" = \"public\".\"store\".\"address_id\" "
                    + "fetch next ? rows only) as t) as \"__rk_address\", "
                    + "\"public\".\"store\".\"store_id\" "
                    + "from \"public\".\"store\" order by \"public\".\"store\".\"store_id\" asc "
                    + "fetch next ? rows only");
    }

    @Test
    void multisetChild_correlatedSubqueryInTheParentSelectList() {
        execute("{ customers(active: true) { firstName address { address } } }");
        assertThat(SQL_LOG)
            .as("multiset child: the inline Address child rides the parent statement as a "
                + "correlated jsonb aggregate, one statement total")
            .containsExactly(
                "select \"public\".\"customer\".\"first_name\", "
                    + "(select coalesce(jsonb_agg(jsonb_build_array(t.\"v0\")), jsonb_build_array()) "
                    + "from (select \"customer_a0\".\"address\" as \"v0\" "
                    + "from \"public\".\"address\" as \"customer_a0\" "
                    + "where \"customer_a0\".\"address_id\" = \"public\".\"customer\".\"address_id\" "
                    + "fetch next ? rows only) as t) as \"__rk_address\" "
                    + "from \"public\".\"customer\" where \"public\".\"customer\".\"activebool\" = ? "
                    + "order by \"public\".\"customer\".\"customer_id\" asc");
    }

    @Test
    void polymorphicRoot_narrowUnionThenPerTypenameSelects() {
        execute("{ search { name } }");
        assertThat(SQL_LOG)
            .as("polymorphic root: stage 1 is the narrow UNION ALL (typename, pk, sort), stage 2 "
                + "is one VALUES-joined SELECT per participant; VALUES arity rides the seed rows")
            .containsExactlyInAnyOrder(
                "select 'actor' as \"__typename\", \"public\".\"actor\".\"actor_id\" as \"__pk0__\", "
                    + "\"public\".\"actor\".\"actor_id\" as \"__sort__\" from \"public\".\"actor\" "
                    + "union all "
                    + "select 'film' as \"__typename\", \"public\".\"film\".\"film_id\" as \"__pk0__\", "
                    + "\"public\".\"film\".\"film_id\" as \"__sort__\" from \"public\".\"film\" "
                    + "order by \"__sort__\"",
                "select \"public\".\"actor\".\"first_name\", "
                    + "'actor' as \"__typename\", \"actorinput\".\"idx\" from \"public\".\"actor\" "
                    + "join (values (?, ?), (?, ?), (?, ?)) as \"actorinput\" (\"idx\", \"actor_id\") "
                    + "on \"public\".\"actor\".\"actor_id\" = \"actorinput\".\"actor_id\" "
                    + "order by \"actorinput\".\"idx\"",
                "select \"public\".\"film\".\"title\", "
                    + "'film' as \"__typename\", \"filminput\".\"idx\" from \"public\".\"film\" "
                    + "join (values (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)) as \"filminput\" (\"idx\", \"film_id\") "
                    + "on \"public\".\"film\".\"film_id\" = \"filminput\".\"film_id\" "
                    + "order by \"filminput\".\"idx\"");
    }

    @Test
    void overProjectionProbe_oneScalarProjectsOnlyThatScalar() {
        execute("{ customers(active: true) { firstName } }");
        assertThat(SQL_LOG)
            .as("the end of over-projection: only firstName is selected on a type with six child "
                + "fields, and the select list carries exactly that column; the unselected "
                + "children's correlation keys (address_id, store_id) project only when their "
                + "fields are selected, through their own gated arms")
            .containsExactly(
                "select \"public\".\"customer\".\"first_name\" "
                    + "from \"public\".\"customer\" where \"public\".\"customer\".\"activebool\" = ? "
                    + "order by \"public\".\"customer\".\"customer_id\" asc");
    }

    @Test
    void allUnselectedProbe_projectsTheRowPresentSentinel() {
        execute("{ customers(active: true) { __typename } }");
        assertThat(SQL_LOG)
            .as("a selection projecting no column at all: the projection answers with the "
                + "one-field row-present sentinel, deterministic and one-column, instead of an "
                + "empty select list that jOOQ would render as project-everything")
            .containsExactly(
                "select 1 as \"__row_present__\" "
                    + "from \"public\".\"customer\" where \"public\".\"customer\".\"activebool\" = ? "
                    + "order by \"public\".\"customer\".\"customer_id\" asc");
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
