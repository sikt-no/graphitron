package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof of the UPDATE empty patch: a row whose request leaves every settable field
 * out (here the nullable {@code details} group of {@code FilmUpdateNestedInput}) issues no UPDATE,
 * is read back by its key inside the write transaction, and comes back in the payload as an
 * updated row would, one case per write arm (direct projected single, direct VALUES-join list,
 * payload single, payload list). The no-match cases pin that an empty patch on a missing key
 * answers what a non-empty patch on that key answers.
 *
 * <p>"No UPDATE" is read off the row version: {@code xmin} moves on any UPDATE of the row, even
 * one that writes the same values. A {@code TransactionListener} records how the write
 * transaction ended, so a failing batch can be shown to roll back.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class EmptyPatchUpdateExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    /** The write transaction's end markers, {@code commit} or {@code rollback}, in order. */
    static final List<String> TX_END = new CopyOnWriteArrayList<>();

    /** A film id no row carries; each case that needs it deletes it first. */
    private static final int ABSENT_FILM_ID = 999_841;

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
        dsl.configuration().set(new org.jooq.impl.DefaultTransactionListenerProvider(
            new org.jooq.TransactionListener() {
                @Override public void commitEnd(org.jooq.TransactionContext ctx) { TX_END.add("commit"); }
                @Override public void rollbackEnd(org.jooq.TransactionContext ctx) { TX_END.add("rollback"); }
            }));
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private static ExecutionResult executeRaw(String query) {
        TX_END.clear();
        return graphql.execute(Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build());
    }

    private static Map<String, Object> execute(String query) {
        var result = executeRaw(query);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private static String transactionEnd() {
        return TX_END.stream().findFirst().orElseThrow();
    }

    private static int insertFilm(String title, String description) {
        int id = dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("description"), description)
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
        return id;
    }

    /** A film's columns and its row version: {@code xmin} moves on any UPDATE of the row, even one that writes the same values. */
    private static Map<String, Object> dbRow(int filmId) {
        return dsl.select(DSL.field("title"), DSL.field("description"), DSL.field("xmin::text").as("xmin"))
            .from(DSL.table("film"))
            .where(DSL.field("film_id", Integer.class).eq(filmId))
            .fetchOne().intoMap();
    }

    private static void deleteFilm(int filmId) {
        dsl.deleteFrom(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(filmId)).execute();
    }

    // ---- direct projected, single ---------------------------------------------------------------

    @Test
    void directSingle_emptyPatch_echoesTheRowWithoutAnUpdate() {
        String title = "EMPTY-PATCH-1-" + UUID.randomUUID();
        int id = insertFilm(title, "desc");
        var before = dbRow(id);
        try {
            Map<String, Object> data = execute("""
                mutation { updateFilmNested(in: { filmId: %d }) { filmId title } }
                """.formatted(id));
            assertThat((Map<String, Object>) data.get("updateFilmNested"))
                .containsEntry("filmId", id).containsEntry("title", title);
            assertThat(dbRow(id)).as("the row and its version are untouched").isEqualTo(before);
        } finally {
            deleteFilm(id);
        }
    }

    // ---- direct projected, list (VALUES join) ---------------------------------------------------

    @Test
    void directList_emptyPatch_echoesEveryRowWithoutAnUpdate() {
        String titleA = "EMPTY-PATCH-LA-" + UUID.randomUUID();
        String titleB = "EMPTY-PATCH-LB-" + UUID.randomUUID();
        int a = insertFilm(titleA, "da");
        int b = insertFilm(titleB, "db");
        var beforeA = dbRow(a);
        var beforeB = dbRow(b);
        try {
            Map<String, Object> data = execute("""
                mutation { updateFilmsNested(in: [{ filmId: %d }, { filmId: %d }]) { filmId title } }
                """.formatted(a, b));
            assertThat((List<Map<String, Object>>) data.get("updateFilmsNested"))
                .extracting(f -> f.get("title"))
                .containsExactlyInAnyOrder(titleA, titleB);
            assertThat(dbRow(a)).isEqualTo(beforeA);
            assertThat(dbRow(b)).isEqualTo(beforeB);
        } finally {
            deleteFilm(a);
            deleteFilm(b);
        }
    }

    // ---- payload carrier, single ----------------------------------------------------------------

    @Test
    void payloadSingle_emptyPatch_echoesTheRowInThePayload() {
        String title = "EMPTY-PATCH-P1-" + UUID.randomUUID();
        int id = insertFilm(title, "desc");
        var before = dbRow(id);
        try {
            Map<String, Object> data = execute("""
                mutation { updateFilmNestedPayload(in: { filmId: %d }) { film { filmId title } } }
                """.formatted(id));
            var payload = (Map<String, Object>) data.get("updateFilmNestedPayload");
            assertThat((Map<String, Object>) payload.get("film"))
                .containsEntry("filmId", id).containsEntry("title", title);
            assertThat(dbRow(id)).isEqualTo(before);
        } finally {
            deleteFilm(id);
        }
    }

    // ---- payload carrier, list: each row decides on its own -------------------------------------

    @Test
    void payloadList_mixedBatch_writesOnlyTheNonEmptyRowAndKeepsInputOrder() {
        String titleA = "EMPTY-PATCH-MA-" + UUID.randomUUID();
        String titleB = "EMPTY-PATCH-MB-" + UUID.randomUUID();
        String titleC = "EMPTY-PATCH-MC-" + UUID.randomUUID();
        int a = insertFilm(titleA, "da");
        int b = insertFilm(titleB, "db");
        int c = insertFilm(titleC, "dc");
        var beforeA = dbRow(a);
        var beforeC = dbRow(c);
        try {
            Map<String, Object> data = execute("""
                mutation { updateFilmsNestedPayload(in: [
                    { filmId: %d },
                    { filmId: %d, details: { title: "EMPTY-PATCH-RENAMED" } },
                    { filmId: %d }
                ]) { films { filmId title } } }
                """.formatted(a, b, c));
            var payload = (Map<String, Object>) data.get("updateFilmsNestedPayload");
            assertThat((List<Map<String, Object>>) payload.get("films"))
                .as("input order, with only the middle row changed")
                .extracting(f -> f.get("filmId"), f -> f.get("title"))
                .containsExactly(
                    org.assertj.core.groups.Tuple.tuple(a, titleA),
                    org.assertj.core.groups.Tuple.tuple(b, "EMPTY-PATCH-RENAMED"),
                    org.assertj.core.groups.Tuple.tuple(c, titleC));
            assertThat(dbRow(a)).isEqualTo(beforeA);
            assertThat(dbRow(c)).isEqualTo(beforeC);
            assertThat(dbRow(b).get("title")).isEqualTo("EMPTY-PATCH-RENAMED");
        } finally {
            deleteFilm(a);
            deleteFilm(b);
            deleteFilm(c);
        }
    }

    // ---- a missing key: an empty patch answers what a non-empty patch answers -------------------

    @Test
    void directSingle_missingKey_resolvesNullLikeANonEmptyPatch() {
        deleteFilm(ABSENT_FILM_ID);
        Map<String, Object> empty = execute("""
            mutation { updateFilmNested(in: { filmId: %d }) { filmId } }
            """.formatted(ABSENT_FILM_ID));
        Map<String, Object> nonEmpty = execute("""
            mutation { updateFilmNested(in: { filmId: %d, details: { title: "X" } }) { filmId } }
            """.formatted(ABSENT_FILM_ID));
        assertThat(empty).containsEntry("updateFilmNested", null).isEqualTo(nonEmpty);
    }

    @Test
    void payloadList_missingKey_failsTheBatchLikeANonEmptyPatch() {
        deleteFilm(ABSENT_FILM_ID);
        String title = "EMPTY-PATCH-NM-" + UUID.randomUUID();
        int id = insertFilm(title, "desc");
        try {
            var empty = executeRaw("""
                mutation { updateFilmsNestedPayload(in: [
                    { filmId: %d, details: { title: "EMPTY-PATCH-ROLLED-BACK" } },
                    { filmId: %d }
                ]) { films { filmId } } }
                """.formatted(id, ABSENT_FILM_ID));
            assertThat(transactionEnd()).as("the batch rolls back").isEqualTo("rollback");
            assertThat(dbRow(id).get("title")).as("the other row's write is rolled back").isEqualTo(title);

            var nonEmpty = executeRaw("""
                mutation { updateFilmsNestedPayload(in: [
                    { filmId: %d, details: { title: "EMPTY-PATCH-ROLLED-BACK" } },
                    { filmId: %d, details: { title: "X" } }
                ]) { films { filmId } } }
                """.formatted(id, ABSENT_FILM_ID));
            assertThat(transactionEnd()).isEqualTo("rollback");
            assertThat(dbRow(id).get("title")).isEqualTo(title);

            assertThat(empty.getErrors()).as("the no-match error surfaces").hasSize(1);
            assertThat(nonEmpty.getErrors()).hasSize(1);
            assertThat(empty.<Map<String, Object>>getData()).as("the same response, error aside")
                .isEqualTo(nonEmpty.getData());
            assertThat(empty.getErrors().get(0).getPath()).isEqualTo(nonEmpty.getErrors().get(0).getPath());
        } finally {
            deleteFilm(id);
        }
    }
}
