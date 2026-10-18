package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Mutations whose return is re-read after the write: the composite-key single and list UPDATE
 * ({@code updateEmailReply} / {@code updateEmailReplies}) and the discriminated list INSERT
 * ({@code createContents}), whose rows differ in which columns they supply and whose response
 * has to route each row to its own participant.
 *
 * <p>Email fixtures seed their own rows in mailbox 9 in the 700 message_no band (other email
 * tests own the lower ranges). Every write targets rows this class seeds itself or creates under
 * a {@code PIN}-prefixed title, and {@code @AfterEach} deletes both: under {@code -Plocal-db} one
 * PostgreSQL is shared across classes, so a leaked row poisons whichever data-reading class runs
 * next.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class DmlReprojectionExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    private static final int MAILBOX_BOB = 9;

    /**
     * This class's {@code email.message_no} band, and the range its cleanup deletes. Four classes in
     * this module write {@code email}; each owns a hundred-wide band so that one class's cleanup
     * cannot reach another's rows while both are running.
     */
    private static final int MESSAGE_NO_BAND_START = 700;
    private static final int MESSAGE_NO_BAND_END = 799;

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
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @AfterEach
    void revertMutationEffects() {
        // Bounded at both ends: BulkUpdateSetAgreementExecutionTest owns the 300 band in the same
        // mailbox, and a band that runs to infinity is a band that overlaps its neighbours.
        dsl.deleteFrom(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_BOB))
            .and(DSL.field("message_no", Integer.class).between(MESSAGE_NO_BAND_START, MESSAGE_NO_BAND_END))
            .execute();
        dsl.deleteFrom(DSL.table("content"))
            .where(DSL.field("title", String.class).like("PIN %"))
            .execute();
    }

    @Test
    void compositeKeySingleUpdate_writesAndReturnsOnlyTheKeyedRow() {
        seedEmail(700, "before");
        seedEmail(701, "decoy");
        String id = NodeIdEncoder.encode("Email", MAILBOX_BOB, 700);
        var data = execute("mutation { updateEmailReply(in: {id: \"" + id + "\", subject: \"PIN RE\"}) "
            + "{ messageNo subject } }");
        assertThat((Map<String, Object>) data.get("updateEmailReply"))
            .containsEntry("messageNo", 700)
            .containsEntry("subject", "PIN RE");
        assertThat(subjectOf(700)).isEqualTo("PIN RE");
        assertThat(subjectOf(701)).as("same mailbox, other message_no").isEqualTo("decoy");
    }

    @Test
    void compositeKeyListUpdate_writesAndReturnsEachKeyedRow() {
        seedEmail(700, "before A");
        seedEmail(701, "before B");
        String idA = NodeIdEncoder.encode("Email", MAILBOX_BOB, 700);
        String idB = NodeIdEncoder.encode("Email", MAILBOX_BOB, 701);
        var data = execute("mutation { updateEmailReplies(in: ["
            + "{id: \"" + idA + "\", subject: \"PIN RE A\"}, "
            + "{id: \"" + idB + "\", subject: \"PIN RE B\"}"
            + "]) { messageNo subject } }");
        assertThat((List<Map<String, Object>>) data.get("updateEmailReplies"))
            .extracting(r -> r.get("messageNo"), r -> r.get("subject"))
            .containsExactlyInAnyOrder(tuple(700, "PIN RE A"), tuple(701, "PIN RE B"));
        assertThat(subjectOf(700)).isEqualTo("PIN RE A");
        assertThat(subjectOf(701)).isEqualTo("PIN RE B");
    }

    @Test
    void discriminatedListInsert_rowsSupplyingDifferentColumnsRouteToTheirOwnParticipant() {
        var data = execute("mutation { createContents(in: [{title: \"PIN FC2\", contentType: \"FILM\", filmId: 1}, "
            + "{title: \"PIN SC\", contentType: \"SHORT\", description: \"short pin\"}]) "
            + "{ __typename ... on FilmContent { contentId } ... on ShortContent { contentId description } } }");
        var film = content("PIN FC2");
        var shortRow = content("PIN SC");
        assertThat((List<Map<String, Object>>) data.get("createContents"))
            .as("in input order, each row under its own participant")
            .containsExactly(
                Map.of("__typename", "FilmContent", "contentId", film.get("content_id", Integer.class)),
                Map.of("__typename", "ShortContent", "contentId", shortRow.get("content_id", Integer.class),
                    "description", "short pin"));
        assertThat(film.get("film_id", Integer.class)).isEqualTo(1);
        assertThat(film.get("short_description", String.class))
            .as("the column this row did not supply takes its default").isNull();
        assertThat(shortRow.get("film_id", Integer.class)).isNull();
        assertThat(shortRow.get("short_description", String.class)).isEqualTo("short pin");
    }

    private static Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private static void seedEmail(int msgNo, String subject) {
        dsl.insertInto(DSL.table("email"),
                DSL.field("mailbox_id"), DSL.field("message_no"), DSL.field("subject"))
            .values(MAILBOX_BOB, msgNo, subject)
            .execute();
    }

    private static String subjectOf(int msgNo) {
        return dsl.select(DSL.field("subject", String.class))
            .from(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_BOB))
            .and(DSL.field("message_no", Integer.class).eq(msgNo))
            .fetchSingle()
            .value1();
    }

    private static Record content(String title) {
        return dsl.select(DSL.field("content_id"), DSL.field("film_id"), DSL.field("short_description"))
            .from(DSL.table("content"))
            .where(DSL.field("title", String.class).eq(title))
            .fetchSingle();
    }
}
