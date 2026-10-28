package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end execution test for the accessor-derived source path.
 *
 * <p>Cousin of {@link MutationPayloadLifterTest}: instead of the {@code @sourceRow}
 * directive, the parent record-backed type {@code CreateFilmsPayload} carries a typed
 * zero-arg accessor returning {@code List<FilmRecord>}, and the classifier auto-derives a
 * {@link no.sikt.graphitron.rewrite.model.KeyLift.Accessor} lift ({@code Arity.MANY}) without any
 * directive. The DataFetcher dispatches via {@code DataLoader.loadMany}; the rows-method
 * returns one record per element-PK key (1:1 with keys) using {@code scatterSingleByIdx}.
 *
 * <p>The test asserts:
 * <ul>
 *   <li>One JDBC round-trip total: the service hand-rolls payloads (no DB hit on the root),
 *       so the only query is the loadMany-driven batched film lookup.</li>
 *   <li>Both payloads' film lists resolve to the correct {@code Film} rows: payload 0 lists
 *       films [1, 2]; payload 1 lists film [3]. The DataLoader sees three element-PK keys
 *       and re-distributes the three resulting records back to the parent payloads as the
 *       three independent loadMany futures complete.</li>
 * </ul>
 */
@ExecutionTier
class AccessorDerivedSourceTest {

    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();

    @BeforeAll
    static void startDatabase() throws Exception {
        dsl = SakilaDatabase.dsl();


        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
                }
            }));

        graphql = Graphitron.newGraphQL().build();
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @SuppressWarnings("unchecked")
    @Test
    void accessorDerivedManyPayloads_dataLoaderBatchesByElementPk() {
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ recentlyCreatedFilmsBatched { films { filmId title } } }");

        // The service hand-rolls payloads → no root JDBC hit. The accessor-derived DataLoader
        // runs exactly one batched film lookup spanning all three element-PK keys across both
        // parents. Unbatched scatter would fire one query per key (3); an empty-input
        // short-circuit would fire 0.
        assertThat(QUERY_COUNT.get()).isEqualTo(1);

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

    @SuppressWarnings("unchecked")
    @Test
    void accessorDerivedManyPayloads_nullToManyBacking_rendersEmptyListWithoutNpe() {
        // MANY arm null-guard (fork (a)): the second payload's films() accessor returns null (a to-many
        // @table relation that was never populated). buildAccessorKeyMany hoists the accessor result
        // to a typed local and skips the for-loop when it is null, so `keys` stays empty and the
        // existing loadMany(keys, ...) dispatch renders the field as [] — rather than NPEing in the
        // for-each. The present payload still resolves film [1] through the same loader.
        Map<String, Object> data = execute(
            "{ recentlyCreatedFilmsBatchedWithGap { films { filmId } } }");

        List<Map<String, Object>> payloads = (List<Map<String, Object>>) data.get("recentlyCreatedFilmsBatchedWithGap");
        assertThat(payloads).hasSize(2);

        var films0 = (List<Map<String, Object>>) payloads.get(0).get("films");
        var films1 = (List<Map<String, Object>>) payloads.get(1).get("films");

        assertThat(films0).extracting(f -> f.get("filmId")).containsExactly(1);
        // Null to-many backing collapses to the empty-list rendering (null-vs-empty is not
        // meaningful at a to-many's surface), the deliberate asymmetry with the ONE arm's null.
        assertThat(films1).isEmpty();
    }
}
