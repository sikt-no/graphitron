package no.sikt.graphitron.rewrite.test.querydb;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

/**
 * Execution-tier coverage for composite-key {@code @lookupKey} via {@code @table} input type.
 * Behavioural assertions on {@code filmActorsByKey([FilmActorKey!]!)} live alongside the rest
 * of the lookup-field coverage in {@code GraphQLQueryTest}; this class focuses on the
 * load-bearing claim that wasn't being verified: the rendered SQL actually JOINs on both
 * {@code film_id} AND {@code actor_id}, not on either column alone.
 *
 * <p>SQL capture mirrors the {@code FederationEntitiesDispatchTest} pattern: a jOOQ
 * {@link org.jooq.ExecuteListener} appends each rendered statement (lower-cased) to
 * {@link #SQL_LOG}, and tests grep the log for the {@code film_actor} SELECT.
 */
@ExecutionTier
class CompositeKeyLookupQueryTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
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

    @BeforeEach
    void clearSqlLog() {
        SQL_LOG.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {        var input = Graphitron.newExecutionInput(dsl).query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    void compositeKeyLookup_emitsTwoColumnJoinOnFilmIdAndActorId() {
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 1, actorId: 2}, {filmId: 2, actorId: 3}]) { filmId actorId } }");

        assertThat(data).extractingByKey("filmActorsByKey", as(list(Map.class)))
            .hasSize(2)
            .extracting(r -> r.get("filmId") + ":" + r.get("actorId"))
            .containsExactly("1:2", "2:3");

        // The composite-key VALUES + JOIN must reference *both* columns; a single-column
        // join would be a regression. jOOQ renders it as a USING clause with both columns.
        var filmActorSelect = SQL_LOG.stream()
            .filter(s -> s.contains("\"film_actor\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "film_actor SELECT not found in SQL log: " + SQL_LOG));
        assertThat(filmActorSelect)
            .as("composite-key join uses both film_id and actor_id")
            .contains("using (\"film_id\", \"actor_id\")");
    }

    @Test
    void compositeKeyLookup_subset_returnsOnlyMatchingPair() {
        // (film 4, actor 1) is NOT a real film_actor row; (film 1, actor 1) is. The composite
        // join filters out (4,1) and returns (1,1) only — load-bearing for the "missing slot
        // is null/absent" lookup contract on composite keys.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 4, actorId: 1}, {filmId: 1, actorId: 1}]) { filmId actorId } }");

        assertThat(data).extractingByKey("filmActorsByKey", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .containsEntry("filmId", 1)
            .containsEntry("actorId", 1);
    }
}
