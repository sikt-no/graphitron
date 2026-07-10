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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R308 execution-tier coverage for the coherent {@code @service} <em>list</em> carrier
 * ({@code serviceFilmsByIdsAsPayloads(ids): [FilmServicePayload]}, payload {@code FilmServicePayload
 * { film: Film }}). graphql-java iterates the producer's {@code List<FilmRecord>} into the
 * {@code [FilmServicePayload]} list, so each returned {@code FilmRecord} becomes one payload, and each
 * payload's single {@code film} re-fetches through a {@code LOAD_ONE}.
 *
 * <p>The proof this test carries that no lower tier can: graphql-java coalesces the per-element
 * {@code load()} calls within one dispatch cycle, so the whole traversal fires <b>exactly two
 * SELECTs</b> — the producer's own SELECT plus one batched data-field rows-method re-fetch — no matter
 * how many ids are requested. A regression to per-element (unbatched) re-fetch would fire {@code 1 + N}
 * statements and scale with the id count. This is the R308 premise ("post-R305 every RecordTableField
 * emits a LoaderRegistration + rows-method, and graphql-java coalesces per-element load() calls within
 * a dispatch cycle, so no shape produces an N+1 today") observed end-to-end.
 *
 * <p>Sits alongside {@link SingleRecordTableFieldServiceProducerExecutionTest}, whose carriers are all
 * single-arrival ({@code FilmsServicePayload}, {@code FilmServicePayload}); this is the list-arrival
 * sibling the shape verdict now admits explicitly.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ListCarrierServiceProducerExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();

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
                }
            }));

        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private Integer insertFilm(String title) {
        return dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    private void deleteFilmById(int filmId) {
        dsl.deleteFrom(DSL.table("film"))
            .where(DSL.field("film_id", Integer.class).eq(filmId))
            .execute();
    }

    private String randomMarker(String prefix) { return prefix + "-" + UUID.randomUUID(); }

    /**
     * The coherent list carrier resolves every payload's {@code film} through a single batched query.
     * Four ids are requested so an unbatched per-element re-fetch (1 producer + 4 data-field SELECTs =
     * 5) would be visibly distinct from the batched shape (1 producer + 1 batched re-fetch = 2). The
     * exact count is pinned because each statement maps to a documented commitment: the producer's own
     * SELECT, then one DataLoader-coalesced rows-method SELECT over the four PKs.
     */
    @Test
    void listCarrier_perPayloadFilm_resolvesThroughOneBatchedQuery() {
        String a = randomMarker("R308-svc-A");
        String b = randomMarker("R308-svc-B");
        String c = randomMarker("R308-svc-C");
        String d = randomMarker("R308-svc-D");
        Integer idA = insertFilm(a);
        Integer idB = insertFilm(b);
        Integer idC = insertFilm(c);
        Integer idD = insertFilm(d);
        try {
            QUERY_COUNT.set(0);
            Map<String, Object> data = execute("""
                mutation {
                    serviceFilmsByIdsAsPayloads(ids: [%d, %d, %d, %d]) {
                        film { title }
                    }
                }
                """.formatted(idB, idA, idD, idC));

            // 1 producer SELECT (FilmCarrierService.filmsByIds) + 1 batched data-field rows-method
            // SELECT (the four per-payload film LOAD_ONE calls coalesced within one dispatch cycle).
            assertThat(QUERY_COUNT.get())
                .as("R308: the list carrier's per-payload film re-fetch coalesces into one batched "
                    + "query — total 2 SELECTs regardless of id count; a regression to per-element "
                    + "re-fetch would fire 1 + N and scale with the ids")
                .isEqualTo(2);

            // Result shape: one payload per requested id, each carrying its own film, in input order.
            var payloads = (List<Map<String, Object>>) data.get("serviceFilmsByIdsAsPayloads");
            assertThat(payloads).hasSize(4);
            assertThat(payloads)
                .extracting(p -> ((Map<String, Object>) p.get("film")).get("title"))
                .containsExactly(b, a, d, c);
        } finally {
            deleteFilmById(idA);
            deleteFilmById(idB);
            deleteFilmById(idC);
            deleteFilmById(idD);
        }
    }
}
