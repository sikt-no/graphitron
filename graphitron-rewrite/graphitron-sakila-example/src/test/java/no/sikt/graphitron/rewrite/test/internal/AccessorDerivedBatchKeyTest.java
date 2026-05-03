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
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R60: end-to-end execution test for the accessor-derived BatchKey path.
 *
 * <p>Cousin of {@link MutationPayloadLifterTest}: instead of the {@code @batchKeyLifter}
 * directive, the parent {@code @record} type {@code CreateFilmsPayload} carries a typed
 * zero-arg accessor returning {@code List<FilmRecord>}, and the classifier auto-derives
 * {@link no.sikt.graphitron.rewrite.model.BatchKey.AccessorRowKeyedMany} without any
 * directive. The DataFetcher dispatches via {@code DataLoader.loadMany}; the rows-method
 * returns one record per element-PK key (1:1 with keys) using {@code scatterSingleByIdx}.
 *
 * <p>The test asserts:
 * <ul>
 *   <li>One JDBC round-trip total: the service hand-rolls payloads (no DB hit on the root),
 *       so the only query is the loadMany-driven batched film lookup.</li>
 *   <li>The single batched query references {@code film_id} and selects the film table.</li>
 *   <li>Both payloads' film lists resolve to the correct {@code Film} rows: payload 0 lists
 *       films [1, 2]; payload 1 lists film [3]. The DataLoader sees three element-PK keys
 *       and re-distributes the three resulting records back to the parent payloads as the
 *       three independent loadMany futures complete.</li>
 * </ul>
 */
@ExecutionTier
class AccessorDerivedBatchKeyTest {

    static PostgreSQLContainer<?> postgres;
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
            postgres = new PostgreSQLContainer<>("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.impl.DefaultExecuteListener() {
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
    void accessorDerivedManyPayloads_dataLoaderBatchesByElementPk() {
        QUERY_COUNT.set(0);
        SQL_LOG.clear();
        Map<String, Object> data = execute(
            "{ recentlyCreatedFilmsBatched { films { filmId title } } }");

        // The service hand-rolls payloads → no root JDBC hit. The accessor-derived DataLoader
        // runs exactly one batched film lookup spanning all three element-PK keys across both
        // parents. Unbatched scatter would fire one query per key (3); an empty-input
        // short-circuit would fire 0.
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(SQL_LOG).hasSize(1);
        // The single batched query references the film table and the film_id key column from
        // the accessor's element-PK derivation. Three VALUES rows for three keys (film IDs
        // 1, 2, 3) prove the loadMany dispatch shape.
        assertThat(SQL_LOG.get(0)).contains("film_id").contains("\"film\"");
        assertThat(SQL_LOG.get(0)).contains("(values (0, ?), (1, ?), (2, ?))");

        List<Map<String, Object>> payloads = (List<Map<String, Object>>) data.get("recentlyCreatedFilmsBatched");
        assertThat(payloads).hasSize(2);

        var films0 = (List<Map<String, Object>>) payloads.get(0).get("films");
        var films1 = (List<Map<String, Object>>) payloads.get(1).get("films");

        assertThat(films0).extracting(f -> f.get("filmId")).containsExactly(1, 2);
        assertThat(films1).extracting(f -> f.get("filmId")).containsExactly(3);
        // Sanity: titles came from the database, not from the FilmRecord stub the service
        // hand-rolled (which only sets filmId).
        assertThat(films0).extracting(f -> f.get("title"))
            .allSatisfy(t -> assertThat(t).asString().isNotBlank());
    }
}
