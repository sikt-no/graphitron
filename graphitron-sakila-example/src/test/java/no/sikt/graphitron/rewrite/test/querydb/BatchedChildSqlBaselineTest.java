package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batched child family's SQL equivalence baseline, the same idiom as
 * {@link RootLauncherSqlBaselineTest}: whole rendered statements, exact strings and statement
 * counts, one representative query per batched child shape, authored against the pre-cutover
 * {@code SplitRowsMethodEmitter} emission and frozen before the child family folds into the
 * launcher relation. The fold's promise is the launcher item's: shape may move (the rows-method
 * body moves from an opaque emitter composition onto command rows and a renderer), SQL may not.
 * Editing an expected string during the child-family fold is a defect being papered over.
 *
 * <p>The shapes: the list batched child ({@code SplitParent.tags}, the parent-input VALUES
 * derived table joined through the FK chain with the {@code __idx__} scatter key), the
 * single-cardinality batched child ({@code Customer.addressSplit}, one key row, the
 * single-scatter shape), and the batched connection child ({@code Film.actorsConnection}, the
 * {@code ROW_NUMBER() OVER (PARTITION BY __idx__)} per-parent page envelope with its shared
 * count source). Seed cardinalities keep the VALUES row counts stable: two {@code split_parent}
 * rows, one film key, one customer key.
 */
@ExecutionTier
class BatchedChildSqlBaselineTest {

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
    void listBatchedChild_parentInputValuesJoinWithIdxScatterKey() {
        execute("{ splitParents { label tags { tag } } }");
        assertThat(SQL_LOG)
            .as("list batched child: the parent statement, then one batch statement joining the "
                + "parent-input VALUES derived table through the FK chain, idx-keyed for scatter "
                + "(and, as pinned current behaviour, no ORDER BY on the batch: the list-shaped "
                + "batched child renders no ordering)")
            .containsExactly(
                "select \"public\".\"split_parent\".\"label\", \"public\".\"split_parent\".\"parent_code\" "
                    + "from \"public\".\"split_parent\" "
                    + "order by \"public\".\"split_parent\".\"parent_id\" asc",
                "select \"tags_s0\".\"tag\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?), (1, ?)) as \"parentinput\" (\"idx\", \"parent_code\") "
                    + "join \"public\".\"split_parent_tag\" as \"tags_s0\" "
                    + "on \"tags_s0\".\"parent_code\" = \"parentinput\".\"parent_code\"");
    }

    @Test
    void singleBatchedChild_oneKeyRowSingleScatter() {
        execute("{ customerByPk(customerId: 1) { addressSplit { district } } }");
        assertThat(SQL_LOG)
            .as("single-cardinality batched child: the parent statement, then one batch "
                + "statement with a single VALUES key row")
            .containsExactly(
                "select \"public\".\"customer\".\"address_id\" "
                    + "from \"public\".\"customer\" "
                    + "where \"public\".\"customer\".\"customer_id\" = ?",
                "select \"addresssplit_a0\".\"district\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "join \"public\".\"address\" as \"addresssplit_a0\" "
                    + "on \"addresssplit_a0\".\"address_id\" = \"parentinput\".\"address_id\"");
    }

    @Test
    void connectionBatchedChild_rowNumberPartitionedByIdx() {
        execute("{ filmById(film_id: [\"1\"]) { actorsConnection(first: 2) { nodes { firstName } } } }");
        assertThat(SQL_LOG)
            .as("batched connection child: the parent (lookup) statement, then the per-parent "
                + "page envelope, ROW_NUMBER() partitioned by the idx scatter key with the page "
                + "bound riding the outer WHERE")
            .containsExactly(
                "select \"public\".\"film\".\"film_id\" "
                    + "from \"public\".\"film\" "
                    + "join (values (0, ?)) as \"filmbyidinput\" (\"idx\", \"film_id\") using (\"film_id\") "
                    + "order by \"filmbyidinput\".\"idx\"",
                "select \"ranked\".\"first_name\", \"ranked\".\"actor_id\", \"ranked\".\"__idx__\", \"ranked\".\"__rn__\" "
                    + "from (select \"actorsconnection_a1\".\"first_name\", \"actorsconnection_a1\".\"actor_id\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\", "
                    + "row_number() over (partition by \"parentinput\".\"idx\" "
                    + "order by \"actorsconnection_a1\".\"actor_id\" asc) as \"__rn__\" "
                    + "from (values (0, ?)) as \"parentinput\" (\"idx\", \"film_id\") "
                    + "join \"public\".\"film_actor\" as \"actorsconnection_f0\" "
                    + "on \"actorsconnection_f0\".\"film_id\" = \"parentinput\".\"film_id\" "
                    + "join \"public\".\"actor\" as \"actorsconnection_a1\" "
                    + "on \"actorsconnection_f0\".\"actor_id\" = \"actorsconnection_a1\".\"actor_id\" "
                    + "order by \"actorsconnection_a1\".\"actor_id\" asc) as \"ranked\" "
                    + "where \"ranked\".\"__rn__\" <= ?");
    }

    private static void execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("query must execute cleanly; SQL pins compare "
            + "statements, not error paths").isEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data).isNotEmpty();
    }
}
