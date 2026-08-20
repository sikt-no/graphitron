package no.sikt.graphitron.rewrite.test.querydb;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DML family's SQL equivalence baseline, the same idiom as
 * {@link BatchedChildSqlBaselineTest}: whole rendered statements, exact strings and statement
 * counts, one representative mutation per reentry shape, authored against the pre-cutover
 * {@code TypeFetcherGenerator} DML emission and frozen before the projected/discriminated
 * reentry companions fold into the launcher relation. Editing an expected string during the
 * fold is a defect being papered over.
 *
 * <p>Each projected/discriminated pin captures two statements: the DML+RETURNING write inside
 * the transaction, then the reentry companion's re-projection SELECT (plain key equality for
 * single cardinality; the {@code (idx, pk...)} VALUES join ordered by {@code idx} for list).
 * The shapes: projected single INSERT and UPDATE ({@code Mutation.createFilm} /
 * {@code updateFilm}), projected list INSERT and bulk UPDATE ({@code createFilms} /
 * {@code updateFilms}), the composite-PK single and list arms ({@code updateEmailReply} /
 * {@code updateEmailReplies}, row-tuple equality and multi-column VALUES rows), the
 * discriminated single and list arms ({@code createContent} / {@code createContents}, the
 * {@code __discriminator__} re-projection with the discriminator-gated cross-table subselect),
 * and the encoded negative ({@code deleteFilms}: exactly one statement, no companion). UPSERT
 * is retired from the execution corpus and cannot be pinned at this tier.
 *
 * <p>Email fixtures seed their own rows in mailbox 9 at {@code message_no >= 700} (other email
 * tests own the lower ranges) and clean up after; seeding happens before the per-test SQL-log
 * clear so seed statements never enter a pin.
 *
 * <p>Every write targets rows this class seeds itself, and every effect is reverted per test
 * ({@code @AfterEach} deletes the {@code PIN}-titled rows): the {@code -Plocal-db} profile
 * shares one PostgreSQL across test classes, so a leaked row poisons whichever data-reading
 * class runs next, and even a reverted UPDATE of a seed row moves its heap tuple, flipping the
 * row order that unordered queries elsewhere implicitly pin. Seeding happens before the
 * per-test SQL-log clear, so seed statements never enter a pin, and every pinned value is a
 * bind ({@code ?}), so self-seeded ids change no pinned string.
 */
@ExecutionTier
class DmlSqlBaselineTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final java.util.List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final int MAILBOX_BOB = 9;

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
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(java.util.Locale.ROOT));
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearSqlLog() {
        SQL_LOG.clear();
    }

    @AfterEach
    void revertMutationEffects() {
        dsl.deleteFrom(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_BOB))
            .and(DSL.field("message_no", Integer.class).ge(700))
            .execute();
        // Every row this class creates or updates carries a PIN-prefixed title (film and
        // content alike; the UPDATE pins target self-seeded rows, never init.sql's).
        dsl.deleteFrom(DSL.table("content"))
            .where(DSL.field("title", String.class).like("PIN %"))
            .execute();
        dsl.deleteFrom(DSL.table("film"))
            .where(DSL.field("title", String.class).like("PIN %"))
            .execute();
    }

    private static int seedFilm(String title) {
        return dsl.insertInto(DSL.table("film"),
                DSL.field("title"), DSL.field("language_id"))
            .values(title, 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne()
            .value1();
    }

    @Test
    void projectedSingleInsert_writeThenPlainKeyEqualityCompanion() {
        execute("mutation { createFilm(in: {title: \"PIN FILM\", languageId: 1}) { filmId title } }");
        assertThat(SQL_LOG)
            .as("projected single INSERT: the INSERT..RETURNING pk inside the transaction, "
                + "then the companion SELECT re-projecting the return type by plain key equality")
            .containsExactly(
                "insert into \"public\".\"film\" (\"title\", \"language_id\", \"rental_duration\") "
                    + "values (?, ?, default) "
                    + "returning \"public\".\"film\".\"film_id\"",
                "select \"public\".\"film\".\"film_id\", \"public\".\"film\".\"title\" "
                    + "from \"public\".\"film\" "
                    + "where \"public\".\"film\".\"film_id\" = ?");
    }

    @Test
    void projectedSingleUpdate_writeThenPlainKeyEqualityCompanion() {
        int filmId = seedFilm("PIN SEED U1");
        SQL_LOG.clear();
        execute("mutation { updateFilm(in: {filmId: " + filmId + ", title: \"PIN TITLE\"}) { filmId title } }");
        assertThat(SQL_LOG)
            .as("projected single UPDATE: the UPDATE..RETURNING pk, then the same companion "
                + "shape as the INSERT sibling")
            .containsExactly(
                "update \"public\".\"film\" set \"title\" = ? "
                    + "where \"public\".\"film\".\"film_id\" = ? "
                    + "returning \"public\".\"film\".\"film_id\"",
                "select \"public\".\"film\".\"film_id\", \"public\".\"film\".\"title\" "
                    + "from \"public\".\"film\" "
                    + "where \"public\".\"film\".\"film_id\" = ?");
    }

    @Test
    void projectedListInsert_writeThenValuesJoinCompanionOrderedByIdx() {
        execute("mutation { createFilms(in: [{title: \"PIN A\", languageId: 1}, "
            + "{title: \"PIN B\", languageId: 1}]) { filmId title } }");
        assertThat(SQL_LOG)
            .as("projected list INSERT: the multi-row INSERT..RETURNING, then the companion "
                + "joining the returned PKs through the (idx, pk) VALUES table ordered by idx")
            .containsExactly(
                "insert into \"public\".\"film\" (\"title\", \"language_id\", \"rental_duration\") "
                    + "values (?, ?, default), (?, ?, default) "
                    + "returning \"public\".\"film\".\"film_id\"",
                "select \"public\".\"film\".\"film_id\", \"public\".\"film\".\"title\" "
                    + "from \"public\".\"film\" "
                    + "join (values (?, ?), (?, ?)) as \"keysinput\" (\"idx\", \"film_id\") "
                    + "on \"public\".\"film\".\"film_id\" = \"keysinput\".\"film_id\" "
                    + "order by \"keysinput\".\"idx\"");
    }

    @Test
    void projectedListBulkUpdate_valuesFromWriteThenValuesJoinCompanion() {
        int filmA = seedFilm("PIN SEED U2");
        int filmB = seedFilm("PIN SEED U3");
        SQL_LOG.clear();
        execute("mutation { updateFilms(in: [{filmId: " + filmA + ", title: \"PIN C\"}, "
            + "{filmId: " + filmB + ", title: \"PIN D\"}]) { filmId title } }");
        assertThat(SQL_LOG)
            .as("projected bulk UPDATE: the UPDATE .. FROM (VALUES ..) write half, then the "
                + "same VALUES-join companion as the bulk INSERT")
            .containsExactly(
                "update \"public\".\"film\" set \"title\" = \"v\".\"title\" "
                    + "from (values (?, ?), (?, ?)) as \"v\" (\"film_id\", \"title\") "
                    + "where \"public\".\"film\".\"film_id\" = \"v\".\"film_id\" "
                    + "returning \"public\".\"film\".\"film_id\"",
                "select \"public\".\"film\".\"film_id\", \"public\".\"film\".\"title\" "
                    + "from \"public\".\"film\" "
                    + "join (values (?, ?), (?, ?)) as \"keysinput\" (\"idx\", \"film_id\") "
                    + "on \"public\".\"film\".\"film_id\" = \"keysinput\".\"film_id\" "
                    + "order by \"keysinput\".\"idx\"");
    }

    @Test
    void compositeKeySingleUpdate_rowTupleEqualityCompanion() {
        seedEmail(700, "before");
        SQL_LOG.clear();
        String id = NodeIdEncoder.encode("Email", MAILBOX_BOB, 700);
        execute("mutation { updateEmailReply(in: {id: \"" + id + "\", subject: \"PIN RE\"}) "
            + "{ messageNo subject } }");
        assertThat(SQL_LOG)
            .as("composite-PK projected single UPDATE: the companion's key equality is the "
                + "row-tuple form over (mailbox_id, message_no)")
            .containsExactly(
                "update \"public\".\"email\" set \"subject\" = ? "
                    + "where (\"public\".\"email\".\"mailbox_id\" = ? "
                    + "and \"public\".\"email\".\"message_no\" = ?) "
                    + "returning \"public\".\"email\".\"mailbox_id\", \"public\".\"email\".\"message_no\"",
                "select \"public\".\"email\".\"message_no\", \"public\".\"email\".\"subject\" "
                    + "from \"public\".\"email\" "
                    + "where (\"public\".\"email\".\"mailbox_id\", \"public\".\"email\".\"message_no\") = (?, ?)");
    }

    @Test
    void compositeKeyListUpdate_multiColumnValuesRowsCompanion() {
        seedEmail(700, "before A");
        seedEmail(701, "before B");
        SQL_LOG.clear();
        String idA = NodeIdEncoder.encode("Email", MAILBOX_BOB, 700);
        String idB = NodeIdEncoder.encode("Email", MAILBOX_BOB, 701);
        execute("mutation { updateEmailReplies(in: ["
            + "{id: \"" + idA + "\", subject: \"PIN RE A\"}, "
            + "{id: \"" + idB + "\", subject: \"PIN RE B\"}"
            + "]) { messageNo subject } }");
        assertThat(SQL_LOG)
            .as("composite-PK projected bulk UPDATE: the companion's VALUES rows carry "
                + "(idx, mailbox_id, message_no)")
            .containsExactly(
                "update \"public\".\"email\" set \"subject\" = \"v\".\"subject\" "
                    + "from (values (?, ?, ?), (?, ?, ?)) as \"v\" (\"mailbox_id\", \"message_no\", \"subject\") "
                    + "where (\"public\".\"email\".\"mailbox_id\" = \"v\".\"mailbox_id\" "
                    + "and \"public\".\"email\".\"message_no\" = \"v\".\"message_no\") "
                    + "returning \"public\".\"email\".\"mailbox_id\", \"public\".\"email\".\"message_no\"",
                "select \"public\".\"email\".\"message_no\", \"public\".\"email\".\"subject\" "
                    + "from \"public\".\"email\" "
                    + "join (values (?, ?, ?), (?, ?, ?)) as \"keysinput\" (\"idx\", \"mailbox_id\", \"message_no\") "
                    + "on (\"public\".\"email\".\"mailbox_id\" = \"keysinput\".\"mailbox_id\" "
                    + "and \"public\".\"email\".\"message_no\" = \"keysinput\".\"message_no\") "
                    + "order by \"keysinput\".\"idx\"");
    }

    @Test
    void discriminatedSingleInsert_discriminatorReprojectionCompanion() {
        execute("mutation { createContent(in: {title: \"PIN FC\", contentType: \"FILM\", filmId: 1}) "
            + "{ __typename ... on FilmContent { contentId title } ... on ShortContent { contentId } } }");
        assertThat(SQL_LOG)
            .as("discriminated single INSERT: the companion re-projects by PK through the "
                + "__discriminator__ alias and the selection's participant field union, "
                + "restricted to the known discriminator values")
            .containsExactly(
                "insert into \"public\".\"content\" "
                    + "(\"title\", \"content_type\", \"length\", \"short_description\", \"film_id\") "
                    + "values (?, cast(? as \"public\".\"content_kind\"), default, default, ?) "
                    + "returning \"public\".\"content\".\"content_id\"",
                "select \"content\".\"content_type\" as \"__discriminator__\", "
                    + "\"public\".\"content\".\"content_id\", \"public\".\"content\".\"title\" "
                    + "from \"public\".\"content\" "
                    + "where (\"public\".\"content\".\"content_id\" = ? "
                    + "and \"content\".\"content_type\" in (cast(? as \"public\".\"content_kind\"), cast(? as \"public\".\"content_kind\")))");
    }

    @Test
    void discriminatedListInsert_discriminatorValuesJoinCompanion() {
        execute("mutation { createContents(in: [{title: \"PIN FC2\", contentType: \"FILM\", filmId: 1}, "
            + "{title: \"PIN SC\", contentType: \"SHORT\", description: \"short pin\"}]) "
            + "{ __typename ... on FilmContent { contentId } ... on ShortContent { contentId description } } }");
        assertThat(SQL_LOG)
            .as("discriminated list INSERT: the discriminator re-projection plus the "
                + "(idx, pk) VALUES join ordered by idx, in one companion statement")
            .containsExactly(
                "insert into \"public\".\"content\" "
                    + "(\"title\", \"content_type\", \"length\", \"short_description\", \"film_id\") "
                    + "values (?, cast(? as \"public\".\"content_kind\"), default, default, ?), (?, cast(? as \"public\".\"content_kind\"), default, ?, default) "
                    + "returning \"public\".\"content\".\"content_id\"",
                "select \"content\".\"content_type\" as \"__discriminator__\", "
                    + "\"public\".\"content\".\"content_id\", \"public\".\"content\".\"short_description\" "
                    + "from \"public\".\"content\" "
                    + "join (values (?, ?), (?, ?)) as \"keysinput\" (\"idx\", \"content_id\") "
                    + "on \"public\".\"content\".\"content_id\" = \"keysinput\".\"content_id\" "
                    + "where \"content\".\"content_type\" in (cast(? as \"public\".\"content_kind\"), cast(? as \"public\".\"content_kind\")) "
                    + "order by \"keysinput\".\"idx\"");
    }

    @Test
    void encodedListDelete_singleStatementNoCompanion() {
        int filmId = seedFilm("PIN DELETE ME");
        SQL_LOG.clear();
        execute("mutation { deleteFilms(in: [{filmId: " + filmId + "}]) }");
        assertThat(SQL_LOG)
            .as("encoded list DELETE: exactly one statement, the row-tuple IN delete; no "
                + "reentry companion exists for the Encoded arms (the negative that would "
                + "catch a stray companion landing with the fold)")
            .containsExactly(
                "delete from \"public\".\"film\" "
                    + "where (\"public\".\"film\".\"film_id\") in ((?)) "
                    + "returning \"public\".\"film\".\"film_id\"");
    }

    private static void execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("mutation must execute cleanly; SQL pins compare "
            + "statements, not error paths").isEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data).isNotEmpty();
    }

    private static void seedEmail(int msgNo, String subject) {
        dsl.insertInto(DSL.table("email"),
                DSL.field("mailbox_id"), DSL.field("message_no"), DSL.field("subject"))
            .values(MAILBOX_BOB, msgNo, subject)
            .execute();
    }
}
