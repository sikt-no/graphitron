package no.sikt.graphitron.rewrite.test.internal;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
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

/**
 * R102 execution-tier coverage for the list-arm DataLoader-batched multi-table polymorphic
 * child fetcher. Exercises {@code Address.occupants: [AddressOccupant!]!} (union of
 * {@code Customer | Staff}) over the full sakila customer fanout selecting their address
 * occupants; pins the batched-statement count so a regression to per-parent fanout (the
 * pre-R102 shape, which fired ~N+1 statements per parent) fails loudly.
 *
 * <p>Pre-R102: per-parent inline fetcher → one stage-1 UNION ALL plus one per-typename SELECT
 * per typename present, repeated for every parent invocation. The count grew linearly with
 * the customer count.
 *
 * <p>Post-R102: one DataLoader-batched stage-1 UNION ALL with {@code JOIN parentInput} over
 * the distinct address PKs, plus one stage-2 SELECT per participant typename — three child
 * statements regardless of customer count. The top-level customers query brings the total
 * to 4. The dedup over repeated address PKs is exercised because sakila has multiple
 * customers per address.
 *
 * <p>Exact-count is the right grain: each of the four statements maps to a documented
 * architectural commitment (one parent-rows query, one DataLoader-batched stage-1 union, one
 * stage-2 SELECT per participant typename). Upper-bound would let a regression to a 5- or
 * 6-statement intermediate slip through. Forward-pointer: if a future change splits the
 * batched UNION ALL across multiple SQL roundtrips (e.g. per-participant {@code loadMany}
 * dispatch), the count rises and the test re-pins to the new architectural commitment; the
 * exact-count grain stays correct because each new statement still maps to a specific design
 * choice.
 */
@ExecutionTier
class AddressOccupantsListBatchingTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();
    static final List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void startDatabase() throws Exception {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
                    var sql = ctx.sql();
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(java.util.Locale.ROOT));
                }
            }));

        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var context = new GraphitronContext() {
            @Override
            public DSLContext getDslContext(DataFetchingEnvironment env) {
                return dsl;
            }
            @Override
            public <T> T getContextArgument(DataFetchingEnvironment env, String name) {
                return null;
            }
        };

        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(builder -> builder.put(GraphitronContext.class, context))
            .dataLoaderRegistry(new org.dataloader.DataLoaderRegistry())
            .build();

        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @SuppressWarnings("unchecked")
    @Test
    void addressOccupantsListForm_dataLoaderBatchesOnParentPk() {
        QUERY_COUNT.set(0);
        SQL_LOG.clear();

        Map<String, Object> data = execute("""
            { customers {
                customerId
                address { occupants {
                  __typename
                  ... on Customer { customerId }
                  ... on Staff    { staffId }
                }}
              }
            }
            """);

        // 1 customers query + 1 stage-1 batched UNION ALL + 1 per-participant SELECT × 2 typenames
        // (Customer and Staff) = 4 statements regardless of how many parent address PKs appear in
        // the batch (sakila has multiple customers per address, so the dedup is exercised).
        assertThat(QUERY_COUNT.get())
            .as("R102: list-arm DataLoader-batched multi-table polymorphic child fires exactly "
                + "4 statements for the customers→address.occupants fanout (1 customers + 1 "
                + "stage-1 union + 2 stage-2 typename SELECTs); regression to the pre-R102 "
                + "per-parent fanout would scale linearly with customer count")
            .isEqualTo(4);

        // Stage-1 UNION ALL references parentInput VALUES table joining on the parent address PK.
        var stage1 = SQL_LOG.stream()
            .filter(s -> s.contains("union all") && s.contains("\"parent_input\"")
                || s.contains("union all") && s.contains("parentinput"))
            .findFirst();
        assertThat(stage1)
            .as("Exactly one stage-1 union-all SELECT joining the parentInput VALUES table")
            .isPresent();

        // Result shape sanity: every customer payload carries an address with at least one
        // occupant (the customer themselves).
        var customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).isNotEmpty();
        for (var c : customers) {
            var address = (Map<String, Object>) c.get("address");
            var occupants = (List<Map<String, Object>>) address.get("occupants");
            assertThat(occupants).isNotEmpty();
        }
    }
}
