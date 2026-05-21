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
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R158 execution-tier coverage for {@code @service}-backed single-record DML carrier data
 * fields. Exercises the {@code Wrap.TableRecord(target.recordClass())} arm of
 * {@code FetcherEmitter.buildSingleRecordTableFetcherValue} end-to-end: the
 * {@code FilmCarrierService} producer hand-runs a SELECT to return
 * {@code List<FilmRecord>} (MANY) or {@code FilmRecord} (ONE), graphql-java traverses
 * the carrier's data field, the data-field fetcher casts {@code env.getSource()} to the
 * typed record / list, reads PKs via {@code record.get(Tables.FILM.FILM_ID)}, and runs
 * the response SELECT against those PKs.
 *
 * <p>Mirrors {@code DmlBulkMutationsExecutionTest}'s {@code FilmsPayload} surface but
 * lands on the {@code Wrap.TableRecord} dispatch arm instead of {@code Wrap.Record}.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class SingleRecordTableFieldServiceProducerExecutionTest {

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

        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private Map<String, Object> execute(String query) {        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
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

    // ===== MANY arm =====

    /**
     * MANY arm end-to-end: the {@code @service} mutation returns {@code List<FilmRecord>};
     * the data-field fetcher reads PKs off the typed records, runs the response SELECT,
     * and projects films in input order via the R141 PK-keyed-map indirection.
     */
    @Test
    void serviceFilmsByIds_returnsFilmsInInputOrder() {
        String a = randomMarker("R158-svc-A");
        String b = randomMarker("R158-svc-B");
        String c = randomMarker("R158-svc-C");
        Integer idA = insertFilm(a);
        Integer idB = insertFilm(b);
        Integer idC = insertFilm(c);
        try {
            // Deliberate non-PK-ordered input: B before A before C. The PK-keyed-map walks
            // input order, so the response array follows the input order regardless of the
            // SELECT's underlying scan order.
            Map<String, Object> data = execute("""
                mutation {
                    serviceFilmsByIds(ids: [%d, %d, %d]) {
                        films { title }
                    }
                }
                """.formatted(idB, idA, idC));
            var payload = (Map<String, Object>) data.get("serviceFilmsByIds");
            var films = (List<Map<String, Object>>) payload.get("films");
            assertThat(films).hasSize(3);
            assertThat(films).extracting(r -> r.get("title")).containsExactly(b, a, c);
        } finally {
            deleteFilmById(idA);
            deleteFilmById(idB);
            deleteFilmById(idC);
        }
    }

    /**
     * MANY-arm composite-PK end-to-end: the {@code @service} mutation returns
     * {@code List<FilmActorRecord>} for the two-PK {@code film_actor} table. The data-field
     * fetcher reads the typed PK pair off each record via
     * {@code r.get(Tables.FILM_ACTOR.ACTOR_ID)} / {@code r.get(Tables.FILM_ACTOR.FILM_ID)},
     * runs the response SELECT with the {@code row(actor_id, film_id).in(...)} predicate,
     * and projects rows in input order via the R141 composite-PK-keyed map walk (map keys
     * are {@code List.of(r.get(actor_id), r.get(film_id))}).
     *
     * <p>Covers the typed paths in
     * {@code FetcherEmitter.buildSingleRecordTableFetcherValueTableRecordWrap} unique to the
     * composite-PK case; the single-PK arm is covered by
     * {@link #serviceFilmsByIds_returnsFilmsInInputOrder}, and the pipeline-tier
     * {@code serviceProducer_many_compositePk_admitsAsWrapTableRecord} pins the registered
     * {@code SourceKey.columns} shape but does not exercise the emitted code at request time.
     */
    @Test
    void serviceFilmActorsByKeys_returnsRowsInInputOrder() {
        // film_actor seed (init.sql:264): (1,1), (2,1), (1,2), (3,2), (1,3), (2,4), (3,5).
        // Deliberately non-PK-ordered input: (3,5) before (1,1) before (2,4). The composite-
        // PK-keyed map walks input order, so the response array follows the input pair order
        // regardless of the SELECT's underlying scan order.
        Map<String, Object> data = execute("""
            mutation {
                serviceFilmActorsByKeys(actorIds: [3, 1, 2], filmIds: [5, 1, 4]) {
                    filmActors { actorId filmId }
                }
            }
            """);
        var payload = (Map<String, Object>) data.get("serviceFilmActorsByKeys");
        var rows = (List<Map<String, Object>>) payload.get("filmActors");
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> List.of(r.get("actorId"), r.get("filmId")))
            .containsExactly(List.of(3, 5), List.of(1, 1), List.of(2, 4));
    }

    /**
     * Empty source short-circuit: {@code @service} returns an empty {@code List<FilmRecord>};
     * the data-field fetcher's {@code source.isEmpty()} check returns the empty list
     * without running the response SELECT.
     */
    @Test
    void serviceFilmsByIds_emptySource_shortCircuits() {
        Map<String, Object> data = execute("""
            mutation {
                serviceFilmsByIds(ids: []) {
                    films { title }
                }
            }
            """);
        var payload = (Map<String, Object>) data.get("serviceFilmsByIds");
        var films = (List<Map<String, Object>>) payload.get("films");
        assertThat(films).isEmpty();
    }

    // ===== ONE arm =====

    /**
     * ONE arm end-to-end: the {@code @service} mutation returns a single {@code FilmRecord};
     * the data-field fetcher reads the PK via {@code source.get(Tables.FILM.FILM_ID)}
     * and runs the response SELECT.
     */
    @Test
    void serviceFilmById_returnsSingleFilm() {
        String marker = randomMarker("R158-svc-one");
        Integer id = insertFilm(marker);
        try {
            Map<String, Object> data = execute("""
                mutation {
                    serviceFilmById(id: %d) {
                        film { title }
                    }
                }
                """.formatted(id));
            var payload = (Map<String, Object>) data.get("serviceFilmById");
            var film = (Map<String, Object>) payload.get("film");
            assertThat(film).isNotNull();
            assertThat(film.get("title")).isEqualTo(marker);
        } finally {
            deleteFilmById(id);
        }
    }

    /**
     * ONE-arm null source: the {@code @service} mutation returns {@code null} (unknown ID);
     * graphql-java does not traverse the payload type when the parent value is null,
     * so the entire {@code serviceFilmById} result is {@code null}. The data-field fetcher's
     * own {@code source == null} short-circuit is unreachable in this path; it remains in
     * the emitted code for the symmetry with the MANY arm and for future paths where the
     * service might return a non-null payload that wraps a null source on the data slot.
     */
    @Test
    void serviceFilmById_serviceReturnsNull_payloadIsNull() {
        Map<String, Object> data = execute("""
            mutation {
                serviceFilmById(id: 999999999) {
                    film { title }
                }
            }
            """);
        assertThat(data.get("serviceFilmById")).isNull();
    }
}
