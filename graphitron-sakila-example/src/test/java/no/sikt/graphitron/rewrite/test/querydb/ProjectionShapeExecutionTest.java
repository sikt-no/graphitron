package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Selections whose shape decides what the parent statement has to carry: a split child whose
 * correlation key is not selected, one nesting type shared by two hosts on different tables, an
 * inline single child, and a selection that names no column at all.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ProjectionShapeExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger STATEMENT_COUNT = new AtomicInteger();

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
                    STATEMENT_COUNT.incrementAndGet();
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void splitChild_unselectedCorrelationKey_stillBatchesTheChildInOneStatement() {
        // parent_code is the correlation key and the query does not select it, so the parent
        // statement has to carry it on its own for the batch to find each parent's tags.
        STATEMENT_COUNT.set(0);
        var parents = list(execute("{ splitParents { label tags { tag } } }"), "splitParents");
        assertThat(STATEMENT_COUNT.get())
            .as("one parent statement and one batched child statement, whatever the parent count")
            .isEqualTo(2);
        assertThat(parents).extracting(p -> p.get("label")).containsExactly("Alpha parent", "Beta parent");
        assertThat(tags(parents.get(0))).containsExactly("a-one", "a-two");
        assertThat(tags(parents.get(1))).containsExactly("b-one");
    }

    @Test
    void sharedNestingType_resolvesAgainstEachHostsOwnTable() {
        var customers = list(execute("{ customers(active: true) { location { address { district } } } }"),
            "customers");
        assertThat(customers).extracting(ProjectionShapeExecutionTest::district)
            .containsExactly("Alberta", "Lazio", "Alberta");

        var stores = (List<Map<String, Object>>) ((Map<String, Object>) execute(
            "{ stores { nodes { location { address { district } } } } }").get("stores")).get("nodes");
        assertThat(stores).extracting(ProjectionShapeExecutionTest::district)
            .containsExactly("Alberta", "Lazio");
    }

    @Test
    void multisetChild_resolvesEachParentsOwnRow() {
        var customers = list(execute("{ customers(active: true) { firstName address { address } } }"),
            "customers");
        assertThat(customers)
            .extracting(c -> c.get("firstName"), c -> ((Map<String, Object>) c.get("address")).get("address"))
            .containsExactly(
                tuple("Mary", "47 MySakila Drive"),
                tuple("Patricia", "28 MySQL Boulevard"),
                tuple("Linda", "23 Workhaven Lane"));
    }

    @Test
    void allUnselectedProbe_returnsOneEntryPerRow() {
        var customers = list(execute("{ customers(active: true) { __typename } }"), "customers");
        assertThat(customers).hasSize(3).allSatisfy(c -> assertThat(c).containsExactly(Map.entry("__typename", "Customer")));
    }

    private static Object district(Map<String, Object> host) {
        var location = (Map<String, Object>) host.get("location");
        return ((Map<String, Object>) location.get("address")).get("district");
    }

    private static List<Object> tags(Map<String, Object> parent) {
        return ((List<Map<String, Object>>) parent.get("tags")).stream().map(t -> t.get("tag")).toList();
    }

    private static List<Map<String, Object>> list(Map<String, Object> data, String field) {
        return (List<Map<String, Object>>) data.get(field);
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
