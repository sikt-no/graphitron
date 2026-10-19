package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for a service's returned list with gaps: a {@code null} element, or a
 * record whose key has no live row. Every list a service fills keeps the records that exist and
 * leaves the others out, whether the list is the field's own return type, a carrier payload's
 * {@code @table} list field (with or without an {@code errors} channel), a carrier's
 * {@code [ID!]} field, or a class-backed payload's list accessor. The service answers before the
 * fields resolve, so a throw or a {@code null} list here hides rows a mutation already committed.
 *
 * <p>Each case inserts two films, {@code A} and {@code B}, and a third it deletes again at once,
 * so the deleted film's id is a key the table has no row for. The services hand the films back in
 * the order {@code [B, missing, A]}, so an assertion on order also pins that the drop keeps the
 * service's order for the rows that remain.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ServiceListGapExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    String titleA;
    String titleB;
    Integer idA;
    Integer idB;
    Integer idMissing;

    @BeforeAll
    static void startDatabase() {
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

    @BeforeEach
    void seedFilms() {
        titleA = "gap-A-" + UUID.randomUUID();
        titleB = "gap-B-" + UUID.randomUUID();
        idA = insertFilm(titleA);
        idB = insertFilm(titleB);
        idMissing = insertFilm("gap-deleted-" + UUID.randomUUID());
        deleteFilmById(idMissing);
    }

    @AfterEach
    void removeFilms() {
        deleteFilmById(idA);
        deleteFilmById(idB);
    }

    private ExecutionResult run(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }

    private Map<String, Object> execute(String query) {
        var result = run(query);
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

    private String gappedIds() {
        return "[%d, %d, %d]".formatted(idB, idMissing, idA);
    }

    private List<Object> titles(Map<String, Object> data, String field) {
        var payload = (Map<String, Object>) data.get(field);
        var films = (List<Map<String, Object>>) payload.get("films");
        assertThat(films).as("the list field renders rather than nulling").isNotNull();
        return films.stream().map(f -> f == null ? null : f.get("title")).toList();
    }

    @Test
    void carrier_nullElement_dropsAndKeepsTheKnownFilms() {
        // The reported failure: the service returns null for an id it does not know.
        var data = execute("mutation { serviceFilmsByIdsWithNulls(ids: %s) { films { title } } }"
            .formatted(gappedIds()));
        assertThat(titles(data, "serviceFilmsByIdsWithNulls")).containsExactly(titleB, titleA);
    }

    @Test
    void carrier_keyWithNoRow_dropsAndKeepsTheKnownFilms() {
        var data = execute("mutation { serviceFilmsByIdsKeyOnly(ids: %s) { films { title } } }"
            .formatted(gappedIds()));
        assertThat(titles(data, "serviceFilmsByIdsKeyOnly")).containsExactly(titleB, titleA);
    }

    @Test
    void nullableElementCarrier_nullElement_leavesNoNullSlot() {
        var data = execute("mutation { serviceNullableFilmsByIdsWithNulls(ids: %s) { films { title } } }"
            .formatted(gappedIds()));
        assertThat(titles(data, "serviceNullableFilmsByIdsWithNulls")).containsExactly(titleB, titleA);
    }

    @Test
    void nullableElementCarrier_keyWithNoRow_leavesNoNullSlot() {
        var data = execute("mutation { serviceNullableFilmsByIdsKeyOnly(ids: %s) { films { title } } }"
            .formatted(gappedIds()));
        assertThat(titles(data, "serviceNullableFilmsByIdsKeyOnly")).containsExactly(titleB, titleA);
    }

    @Test
    void errorsCarrier_nullElement_dropsWithNoErrors() {
        var data = execute("""
            mutation { serviceFilmsByIdsWithNullsWithErrors(ids: %s) {
                films { title }
                errors { __typename }
            } }""".formatted(gappedIds()));
        assertThat(titles(data, "serviceFilmsByIdsWithNullsWithErrors")).containsExactly(titleB, titleA);
        var payload = (Map<String, Object>) data.get("serviceFilmsByIdsWithNullsWithErrors");
        assertThat(payload.get("errors")).isNull();
    }

    @Test
    void errorsCarrier_nullList_rendersNullFilmsAndNullErrors() {
        // The service returns null for an empty id list; the Success(null) source escapes in the
        // data field instead of throwing.
        var data = execute("""
            mutation { serviceFilmsByIdsWithNullsWithErrors(ids: []) {
                films { title }
                errors { __typename }
            } }""");
        var payload = (Map<String, Object>) data.get("serviceFilmsByIdsWithNullsWithErrors");
        assertThat(payload).as("the payload renders").isNotNull();
        assertThat(payload.get("films")).isNull();
        assertThat(payload.get("errors")).isNull();
    }

    @Test
    void rootService_nullElement_dropsAndKeepsTheKnownFilms() {
        var data = execute("{ filmsByServiceWithNulls(ids: [%d, null, %d]) { title } }"
            .formatted(idB, idA));
        var films = (List<Map<String, Object>>) data.get("filmsByServiceWithNulls");
        assertThat(films).extracting(f -> f.get("title")).containsExactly(titleB, titleA);
    }

    @Test
    void rootService_onlyNullElements_resolvesEmptyList() {
        // Every element is skipped, so no key reaches the re-select: the empty gate after the
        // loop answers [] rather than issuing an empty VALUES.
        var data = execute("{ filmsByServiceWithNulls(ids: [null, null]) { title } }");
        assertThat((List<Object>) data.get("filmsByServiceWithNulls")).isEmpty();
    }

    @Test
    void idCarrier_nullElement_encodesTheKnownRecordsOnly() {
        var data = execute("mutation { serviceFilmIdsByIdsWithNulls(ids: %s) { filmIds } }"
            .formatted(gappedIds()));
        var payload = (Map<String, Object>) data.get("serviceFilmIdsByIdsWithNulls");
        assertThat((List<Object>) payload.get("filmIds"))
            .containsExactly(NodeIdEncoder.encodeFilm(idB), NodeIdEncoder.encodeFilm(idA));
    }

    @Test
    void accessorMany_keyWithNoRow_dropsFromTheList() {
        // A class-backed payload's typed List<FilmRecord> accessor hands over film 999999, which
        // has no row; the loadMany dispatch answers null for it and the [Film!]! field drops it.
        var data = execute("{ recentlyCreatedFilmsBatchedWithMissingRow { films { filmId } } }");
        var payloads = (List<Map<String, Object>>) data.get("recentlyCreatedFilmsBatchedWithMissingRow");
        assertThat(payloads).hasSize(1);
        var films = (List<Map<String, Object>>) payloads.get(0).get("films");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(1, 2);
    }
}
