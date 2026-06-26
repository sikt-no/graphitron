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
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R342 execution-tier coverage: structural column dedup + value agreement on the <b>bulk</b> UPDATE SET
 * path (the {@code UPDATE t SET c = v.c FROM (VALUES …) AS v(…)} form), the list-input siblings of the
 * single-row {@link NodeIdValueAgreementExecutionTest} and {@link SelfFkNodeIdUpdateExecutionTest}. Both
 * shapes would, before R342, emit a duplicate derived-table column ({@code v(…, c, …, c, …)}) and crash
 * loud; the dedup collapses each to one v-column with a coalesced, agreement-checked cell.
 *
 * <p><b>Within-SET overlap</b> ({@code updateEndorsementsOverlap}, the {@code film_endorsement} fixture):
 * {@code endorsed_film} is SET by both a plain {@code @field} ({@code endorsedFilm}) and a
 * {@code @nodeId(typeName: "Film")} FK reference ({@code filmRef}). Agreeing rows update the agreed value;
 * a disagreeing row throws before the DML and the batch rolls back; an asymmetric list (only one of the
 * two writers supplied, uniform across rows) still updates the column from the present writer — the
 * behavioural pin on the shared-column disjunction presence gate.
 *
 * <p><b>WHERE∩SET overlap</b> ({@code updateEmailReplies}, the {@code email} self-FK fixture): {@code id}
 * pins the row (WHERE {@code (mailbox_id, message_no)}) while the self-FK {@code inReplyTo} routes
 * {@code (mailbox_id, in_reply_to_no)} to SET, so {@code mailbox_id} sits in both partitions. The dedup
 * supplies it once (from the WHERE side); the per-row WHERE∩SET agreement asserts the {@code id}- and
 * {@code inReplyTo}-decoded mailboxes match before the DML. Agree → repoint {@code in_reply_to_no} with
 * the {@code mailbox_id} SET a no-op; one disagreeing row → throw, batch rolls back; an omitted nullable
 * {@code inReplyTo} updates {@code subject} only. This pins that deleting the walker's bulk self-FK reject
 * yields correct emission, not the reintroduced crash.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class BulkUpdateSetAgreementExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    // Seeded in init.sql: mailbox 9 (bob) + root email (9, 1). This test owns message_no >= 300 in
    // mailbox 9, distinct from SelfFkNodeIdUpdateExecutionTest's mailbox-5 rows, so the two never collide.
    private static final int MAILBOX_BOB = 9;
    private static final int MAILBOX_ALICE = 5;
    private static final int ROOT_MESSAGE_NO = 1;
    private static final String NOTE_PREFIX = "R342B-";

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
    void cleanUp() {
        dsl.deleteFrom(DSL.table("film_endorsement"))
            .where(DSL.field("note", String.class).like(NOTE_PREFIX + "%"))
            .execute();
        dsl.deleteFrom(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_BOB))
            .and(DSL.field("message_no", Integer.class).ge(300))
            .execute();
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        return graphql.execute(input);
    }

    // ------------------------------------------------------------------ within-SET overlap

    @Test
    void bulkWithinSetOverlap_rowsAgree_dedupsColumnAndUpdatesAgreedValue() {
        // Two rows, each SET endorsed_film by both endorsedFilm (@field) and filmRef (@nodeId FK ref).
        // The dedup emits endorsed_film once in v(…) (no duplicate-column crash); each row's two writers
        // agree, so the coalesced cell carries the agreed value and both rows update.
        String note = NOTE_PREFIX + UUID.randomUUID();
        int idA = seedEndorsement(1, note);
        int idB = seedEndorsement(1, note);
        String film2 = NodeIdEncoder.encode("Film", 2);
        String film3 = NodeIdEncoder.encode("Film", 3);
        Map<String, Object> data = execute(
            "mutation { updateEndorsementsOverlap(in: ["
            + "{endorsementId: " + idA + ", filmRef: \"" + film2 + "\", endorsedFilm: 2}, "
            + "{endorsementId: " + idB + ", filmRef: \"" + film3 + "\", endorsedFilm: 3}"
            + "]) { endorsedFilm } }");
        assertThat((java.util.List<?>) data.get("updateEndorsementsOverlap")).hasSize(2);
        assertThat(endorsedFilmOf(idA)).isEqualTo(2);
        assertThat(endorsedFilmOf(idB)).isEqualTo(3);
    }

    @Test
    void bulkWithinSetOverlap_oneRowDisagrees_throwsAndRollsBackBatch() {
        // Row B's filmRef decodes endorsed_film to 2 but endorsedFilm supplies 3: requireColumnAgreement
        // throws while building the VALUES rows, before the single UPDATE statement runs, so neither row
        // changes (no partial batch).
        String note = NOTE_PREFIX + UUID.randomUUID();
        int idA = seedEndorsement(1, note);
        int idB = seedEndorsement(1, note);
        String film2 = NodeIdEncoder.encode("Film", 2);
        ExecutionResult result = executeRaw(
            "mutation { updateEndorsementsOverlap(in: ["
            + "{endorsementId: " + idA + ", filmRef: \"" + film2 + "\", endorsedFilm: 2}, "
            + "{endorsementId: " + idB + ", filmRef: \"" + film2 + "\", endorsedFilm: 3}"
            + "]) { endorsedFilm } }");
        assertThat(result.getErrors())
            .as("a disagreeing row must surface a value-agreement error").isNotEmpty();
        assertThat(endorsedFilmOf(idA)).as("nothing is updated when any row disagrees").isEqualTo(1);
        assertThat(endorsedFilmOf(idB)).isEqualTo(1);
    }

    @Test
    void bulkWithinSetOverlap_onlyPlainFieldWriterPresent_updatesSharedColumn() {
        // Asymmetric (uniform across rows): only the plain @field endorsedFilm is supplied, filmRef
        // omitted from every row. The shared column must still be in v(…) and update from endorsedFilm —
        // a presence gate keyed only on the (absent) filmRef writer would silently drop endorsed_film.
        String note = NOTE_PREFIX + UUID.randomUUID();
        int idA = seedEndorsement(1, note);
        int idB = seedEndorsement(1, note);
        execute(
            "mutation { updateEndorsementsOverlap(in: ["
            + "{endorsementId: " + idA + ", endorsedFilm: 2}, "
            + "{endorsementId: " + idB + ", endorsedFilm: 3}"
            + "]) { endorsedFilm } }");
        assertThat(endorsedFilmOf(idA)).isEqualTo(2);
        assertThat(endorsedFilmOf(idB)).isEqualTo(3);
    }

    @Test
    void bulkWithinSetOverlap_onlyNodeIdFkWriterPresent_updatesSharedColumn() {
        // The mirror of the above: only the @nodeId FK reference filmRef is supplied, endorsedFilm
        // omitted from every row. The shared column updates from the decoded FK value — the other half of
        // the disjunction presence gate.
        String note = NOTE_PREFIX + UUID.randomUUID();
        int idA = seedEndorsement(1, note);
        int idB = seedEndorsement(1, note);
        String film2 = NodeIdEncoder.encode("Film", 2);
        String film3 = NodeIdEncoder.encode("Film", 3);
        execute(
            "mutation { updateEndorsementsOverlap(in: ["
            + "{endorsementId: " + idA + ", filmRef: \"" + film2 + "\"}, "
            + "{endorsementId: " + idB + ", filmRef: \"" + film3 + "\"}"
            + "]) { endorsedFilm } }");
        assertThat(endorsedFilmOf(idA)).isEqualTo(2);
        assertThat(endorsedFilmOf(idB)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ WHERE∩SET overlap (self-FK)

    @Test
    void bulkSelfFk_rowsAgree_repointInReplyTo_mailboxIdSetIsNoOp() {
        // Two replies in mailbox 9 repoint to the mailbox-9 root. id and inReplyTo agree on mailbox_id
        // (9 == 9): the dedup supplies mailbox_id once (from the WHERE side), the agreement passes, and
        // in_reply_to_no is repointed on both rows while mailbox_id stays 9 (the SET is a no-op).
        seedEmail(300, "before");
        seedEmail(301, "before");
        String idA = NodeIdEncoder.encode("Email", MAILBOX_BOB, 300);
        String idB = NodeIdEncoder.encode("Email", MAILBOX_BOB, 301);
        String parent = NodeIdEncoder.encode("Email", MAILBOX_BOB, ROOT_MESSAGE_NO);
        execute(
            "mutation { updateEmailReplies(in: ["
            + "{id: \"" + idA + "\", inReplyTo: \"" + parent + "\", subject: \"re A\"}, "
            + "{id: \"" + idB + "\", inReplyTo: \"" + parent + "\", subject: \"re B\"}"
            + "]) { messageNo inReplyToNo subject } }");
        assertThat(inReplyToNoOf(300)).isEqualTo(ROOT_MESSAGE_NO);
        assertThat(inReplyToNoOf(301)).isEqualTo(ROOT_MESSAGE_NO);
        assertThat(subjectOf(300)).isEqualTo("re A");
        assertThat(subjectOf(301)).isEqualTo("re B");
        assertThat(mailboxIdOf(300)).as("the row stayed in its mailbox (mailbox_id SET no-op)").isEqualTo(MAILBOX_BOB);
    }

    @Test
    void bulkSelfFk_oneRowDisagrees_throwsAndRollsBackBatch() {
        // Row B points at a parent in a DIFFERENT mailbox (alice's root): id says mailbox_id=9,
        // inReplyTo decodes mailbox_id=5. requireColumnAgreement throws before the DML, so neither row
        // moves and both in_reply_to_no stay NULL.
        seedEmail(300, "before");
        seedEmail(301, "before");
        String idA = NodeIdEncoder.encode("Email", MAILBOX_BOB, 300);
        String idB = NodeIdEncoder.encode("Email", MAILBOX_BOB, 301);
        String parentSameMailbox = NodeIdEncoder.encode("Email", MAILBOX_BOB, ROOT_MESSAGE_NO);
        String parentOtherMailbox = NodeIdEncoder.encode("Email", MAILBOX_ALICE, ROOT_MESSAGE_NO);
        ExecutionResult result = executeRaw(
            "mutation { updateEmailReplies(in: ["
            + "{id: \"" + idA + "\", inReplyTo: \"" + parentSameMailbox + "\", subject: \"ok\"}, "
            + "{id: \"" + idB + "\", inReplyTo: \"" + parentOtherMailbox + "\", subject: \"bad\"}"
            + "]) { messageNo } }");
        assertThat(result.getErrors())
            .as("a cross-mailbox self-FK row must surface a value-agreement error").isNotEmpty();
        assertThat(inReplyToNoOf(300)).as("nothing is updated when any row disagrees").isNull();
        assertThat(inReplyToNoOf(301)).isNull();
        assertThat(subjectOf(300)).isEqualTo("before");
    }

    @Test
    void bulkSelfFk_omittedNullableSelfFk_updatesSubjectOnly() {
        // inReplyTo omitted on every row: the self-FK contributes nothing, no WHERE∩SET agreement fires,
        // in_reply_to_no stays NULL, and only subject updates.
        seedEmail(300, "before");
        seedEmail(301, "before");
        String idA = NodeIdEncoder.encode("Email", MAILBOX_BOB, 300);
        String idB = NodeIdEncoder.encode("Email", MAILBOX_BOB, 301);
        execute(
            "mutation { updateEmailReplies(in: ["
            + "{id: \"" + idA + "\", subject: \"u1\"}, "
            + "{id: \"" + idB + "\", subject: \"u2\"}"
            + "]) { messageNo inReplyToNo subject } }");
        assertThat(subjectOf(300)).isEqualTo("u1");
        assertThat(subjectOf(301)).isEqualTo("u2");
        assertThat(inReplyToNoOf(300)).as("an omitted self-FK leaves in_reply_to_no NULL").isNull();
        assertThat(inReplyToNoOf(301)).isNull();
    }

    // ------------------------------------------------------------------ fixture helpers

    private int seedEndorsement(int endorsedFilm, String note) {
        return dsl.insertInto(DSL.table("film_endorsement"))
            .set(DSL.field("endorsed_film"), endorsedFilm)
            .set(DSL.field("note"), note)
            .returningResult(DSL.field("endorsement_id", Integer.class))
            .fetchOne().value1();
    }

    private Integer endorsedFilmOf(int endorsementId) {
        return dsl.select(DSL.field("endorsed_film", Integer.class))
            .from(DSL.table("film_endorsement"))
            .where(DSL.field("endorsement_id", Integer.class).eq(endorsementId))
            .fetchOne(DSL.field("endorsed_film", Integer.class));
    }

    private void seedEmail(int msgNo, String subject) {
        dsl.insertInto(DSL.table("email"),
                DSL.field("mailbox_id"), DSL.field("message_no"), DSL.field("subject"))
            .values(MAILBOX_BOB, msgNo, subject)
            .execute();
    }

    private Integer inReplyToNoOf(int msgNo) {
        return dsl.select(DSL.field("in_reply_to_no", Integer.class))
            .from(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_BOB))
            .and(DSL.field("message_no", Integer.class).eq(msgNo))
            .fetchOne(DSL.field("in_reply_to_no", Integer.class));
    }

    private Integer mailboxIdOf(int msgNo) {
        return dsl.select(DSL.field("mailbox_id", Integer.class))
            .from(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_BOB))
            .and(DSL.field("message_no", Integer.class).eq(msgNo))
            .fetchOne(DSL.field("mailbox_id", Integer.class));
    }

    private String subjectOf(int msgNo) {
        return dsl.select(DSL.field("subject", String.class))
            .from(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_BOB))
            .and(DSL.field("message_no", Integer.class).eq(msgNo))
            .fetchOne(DSL.field("subject", String.class));
    }
}
