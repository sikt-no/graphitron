package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

/**
 * Execution-tier coverage for composite-key {@code @lookupKey} via {@code @table} input type.
 * Behavioural assertions on {@code filmActorsByKey([FilmActorKey!]!)} live alongside the rest
 * of the lookup-field coverage in {@code GraphQLQueryTest}; this class pins that both key
 * columns take part in the match: a key resolves only when {@code film_id} and
 * {@code actor_id} both match a row.
 */
@ExecutionTier
class CompositeKeyLookupQueryTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

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
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    void compositeKeyLookup_returnsEachRequestedPair() {
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 1, actorId: 2}, {filmId: 2, actorId: 3}]) { filmId actorId } }");

        assertThat(data).extractingByKey("filmActorsByKey", as(list(Map.class)))
            .hasSize(2)
            .extracting(r -> r.get("filmId") + ":" + r.get("actorId"))
            .containsExactly("1:2", "2:3");
    }

    @Test
    void compositeKeyLookup_subset_returnsOnlyMatchingPair() {
        // (film 2, actor 2) is NOT a real film_actor row; (film 1, actor 1) is. Both keys keep
        // their slot: the unmatched composite holds null at index 0 and the matched one lands at
        // index 1, which is the slot-per-key lookup contract on composite keys.
        //
        // The unmatched pair has to be one no other class writes. Test classes in this module run
        // concurrently against one database, and DmlBulkMutationsExecutionTest seeds real
        // film_actor rows for (1, 4), (2, 3), (3, 3) and (3, 4), removing them afterwards. Any of
        // those four read as matched for as long as that class holds them, which fails this test on
        // a row it never wrote. film_actor's seed is (1,1), (2,1), (1,2), (3,2), (1,3), (2,4),
        // (3,5), so (film 2, actor 2) is absent from the fixture and outside every seeded pair.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 2, actorId: 2}, {filmId: 1, actorId: 1}]) { filmId actorId } }");

        assertThat(data).extractingByKey("filmActorsByKey", as(LIST))
            .hasSize(2)
            .satisfies(rows -> assertThat(rows.get(0)).as("the unmatched composite keeps its slot").isNull())
            .element(1, as(MAP))
            .containsEntry("filmId", 1)
            .containsEntry("actorId", 1);
    }
}
