package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <p>Statements are captured with an {@code ExecuteListener}; a {@code TransactionListener} marks
 * the write transaction's {@code begin} and {@code commit} / {@code rollback} in the same log, so a
 * case can say which statements ran inside it. The follow-up re-select of the payload runs after
 * the transaction and is the same as for a non-empty patch, so the cases pin only the write half.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class EmptyPatchUpdateExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final List<String> LOG = new CopyOnWriteArrayList<>();

    /** A film id no row carries; each case that needs it deletes it first. */
    private static final int ABSENT_FILM_ID = 999_841;

    private static final String KEY_READ =
        "select \"public\".\"film\".\"film_id\" from \"public\".\"film\" "
            + "where \"public\".\"film\".\"film_id\" = ?";

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
                    var sql = ctx.sql();
                    if (sql != null) LOG.add(sql.toLowerCase(Locale.ROOT));
                }
            }));
        dsl.configuration().set(new org.jooq.impl.DefaultTransactionListenerProvider(
            new org.jooq.TransactionListener() {
                @Override public void beginEnd(org.jooq.TransactionContext ctx) { LOG.add("begin"); }
                @Override public void commitEnd(org.jooq.TransactionContext ctx) { LOG.add("commit"); }
                @Override public void rollbackEnd(org.jooq.TransactionContext ctx) { LOG.add("rollback"); }
            }));
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearLog() {
        LOG.clear();
    }

    private static ExecutionResult executeRaw(String query) {
        LOG.clear();
        return graphql.execute(Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build());
    }

    private static Map<String, Object> execute(String query) {
        var result = executeRaw(query);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    /** The statements between the write transaction's {@code begin} and its end marker. */
    private static List<String> writeTransaction() {
        int begin = LOG.indexOf("begin");
        assertThat(begin).as("a write transaction ran: " + LOG).isNotNegative();
        var inside = new ArrayList<String>();
        for (var entry : LOG.subList(begin + 1, LOG.size())) {
            if (entry.equals("commit") || entry.equals("rollback")) return inside;
            inside.add(entry);
        }
        throw new AssertionError("the write transaction never ended: " + LOG);
    }

    private static String transactionEnd() {
        return LOG.stream().filter(e -> e.equals("commit") || e.equals("rollback")).findFirst().orElseThrow();
    }

    private static void assertNoUpdate() {
        assertThat(LOG).as("an empty patch issues no UPDATE").noneMatch(sql -> sql.startsWith("update"));
    }

    private static int insertFilm(String title, String description) {
        int id = dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("description"), description)
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
        LOG.clear();
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
            assertNoUpdate();
            assertThat(writeTransaction()).as("the key read is the write transaction's only statement")
                .containsExactly(KEY_READ);
            assertThat(dbRow(id)).as("the row and its version are untouched").isEqualTo(before);
        } finally {
            deleteFilm(id);
        }
    }

    // ---- direct projected, list (VALUES join) ---------------------------------------------------

    @Test
    void directList_emptyPatch_readsEveryRowThroughTheValuesJoin() {
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
            assertNoUpdate();
            assertThat(writeTransaction()).singleElement().satisfies(sql -> assertThat(sql)
                .as("the key read joins the same VALUES table the UPDATE would have")
                .startsWith("select \"public\".\"film\".\"film_id\" from \"public\".\"film\" join (values ")
                .contains(" on \"public\".\"film\".\"film_id\" = \"v\".\"film_id\""));
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
            assertNoUpdate();
            assertThat(writeTransaction()).containsExactly(KEY_READ);
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
            assertThat(writeTransaction())
                .as("key read, UPDATE, key read: one statement per row, in row order")
                .satisfiesExactly(
                    sql -> assertThat(sql).isEqualTo(KEY_READ),
                    sql -> assertThat(sql).startsWith("update \"public\".\"film\" set \"title\" = ?"),
                    sql -> assertThat(sql).isEqualTo(KEY_READ));
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
        assertNoUpdate();
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
            assertFirstRowUpdatesAndSecondIsRead();
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

    /** The batch's first row is a real UPDATE; the empty-patch row after it is the key read. */
    private static void assertFirstRowUpdatesAndSecondIsRead() {
        assertThat(writeTransaction())
            .satisfiesExactly(
                sql -> assertThat(sql).startsWith("update"),
                sql -> assertThat(sql).isEqualTo(KEY_READ));
    }
}
