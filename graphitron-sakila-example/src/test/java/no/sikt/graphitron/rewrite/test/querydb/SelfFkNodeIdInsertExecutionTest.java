package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage: a self-FK {@code @nodeId @reference} on a Graphitron-owned INSERT,
 * the neutral {@code email} form of the CAMPUS self-FK case, proven end to end through the
 * shared-column dedup + value-agreement.
 *
 * <p>The {@code insertEmailReply} fixture writes {@code email}'s composite PK with two reference
 * carriers that share the {@code mailbox_id} child column: a cross-table {@code @nodeId(typeName:
 * "Mailbox")} ({@code mailboxRef}) and the self-FK {@code @nodeId(typeName: "Email") @reference}
 * ({@code inReplyTo}, whose {@code email_in_reply_to_fk} child columns are {@code (mailbox_id,
 * in_reply_to_no)}). A reply lives in its parent's mailbox, so the two writers normally agree on
 * {@code mailbox_id}.
 *
 * <p>Three cases, matching the spec's matrix: the two writers <b>agree</b> on the shared column (the
 * dedup emits {@code mailbox_id} once and the row inserts with no "column specified more than once"
 * crash); they <b>disagree</b> (different parent mailbox) and {@code requireColumnAgreement} throws
 * before the INSERT so nothing is written; and an <b>omitted</b> nullable self-FK leaves the lone
 * {@code mailboxRef} decode on {@code mailbox_id} with {@code in_reply_to_no} NULL (the presence
 * guard, no agreement check).
 *
 * <p>An explicit {@code null} on the nullable self-FK is a missing reference, not a {@code NULL} for
 * every column it names: the row takes {@code mailbox_id} from {@code mailboxRef} and leaves
 * {@code in_reply_to_no} {@code NULL}, the same row the omitted case gives, on the single-row and the
 * bulk ({@code insertEmailReplies}) arm alike. A value that is not an {@code Email} id still throws.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class SelfFkNodeIdInsertExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    // Seeded in init.sql: mailbox 5 (alice) + root email (5, 1); mailbox 9 (bob) + root email (9, 1).
    private static final int MAILBOX_ALICE = 5;
    private static final int MAILBOX_BOB = 9;
    private static final int ROOT_MESSAGE_NO = 1;

    /**
     * This class's {@code email.message_no} band, and the range its cleanup deletes. Four classes in
     * this module write {@code email}; each owns a hundred-wide band so that one class's cleanup
     * cannot reach another's rows while both are running.
     */
    private static final int MESSAGE_NO_BAND_START = 100;
    private static final int MESSAGE_NO_BAND_END = 199;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }


    @AfterEach
    void cleanUpInsertedReplies() {
        // Each test inserts a reply at a distinct message_no in this class's band, and the cleanup
        // names the band's far end as well as its near one. Four classes in this module write
        // `email`, each owning a hundred-wide band; an open-ended `>= 100` would delete a sibling
        // class's in-flight rows the moment the two ran at the same time. The bulk case writes into
        // both seeded mailboxes, still inside this class's band.
        dsl.deleteFrom(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).in(MAILBOX_ALICE, MAILBOX_BOB))
            .and(DSL.field("message_no", Integer.class).between(MESSAGE_NO_BAND_START, MESSAGE_NO_BAND_END))
            .execute();
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }

    @Test
    void twoWritersAgreeOnSharedMailboxColumn_dedupsAndInsertsReply() {
        // mailboxRef -> Mailbox(5) writes mailbox_id=5; inReplyTo -> Email(5, 1) writes the self-FK
        // child columns (mailbox_id=5, in_reply_to_no=1). Both target mailbox_id=5 → agree. The dedup
        // emits mailbox_id once (no column-twice crash); the row inserts as a reply to the root email,
        // so in_reply_to_no carries the parent's message_no (1).
        int msgNo = 100;
        String mailbox5 = NodeIdEncoder.encode("Mailbox", MAILBOX_ALICE);
        String parentEmail = NodeIdEncoder.encode("Email", MAILBOX_ALICE, ROOT_MESSAGE_NO);
        Map<String, Object> data = execute(
            "mutation { insertEmailReply(in: {mailboxRef: \"" + mailbox5 + "\", messageNo: " + msgNo
            + ", subject: \"re: root\", inReplyTo: \"" + parentEmail + "\"})"
            + " { messageNo inReplyToNo } }");
        Map<String, Object> row = (Map<String, Object>) data.get("insertEmailReply");
        assertThat(row).extractingByKey("messageNo").isEqualTo(msgNo);
        assertThat(row)
            .as("the self-FK child column in_reply_to_no carries the decoded parent message_no")
            .extractingByKey("inReplyToNo").isEqualTo(ROOT_MESSAGE_NO);
    }

    @Test
    void twoWritersDisagreeOnSharedMailboxColumn_throwsAndInsertsNothing() {
        // mailboxRef -> Mailbox(5) writes mailbox_id=5; inReplyTo -> Email(9, 1) decodes a parent in a
        // DIFFERENT mailbox, writing mailbox_id=9. The two writers disagree on mailbox_id, so
        // requireColumnAgreement throws before the INSERT runs — the mutation surfaces an error and
        // no row is written.
        int msgNo = 101;
        String mailbox5 = NodeIdEncoder.encode("Mailbox", MAILBOX_ALICE);
        String parentInOtherMailbox = NodeIdEncoder.encode("Email", MAILBOX_BOB, ROOT_MESSAGE_NO);
        ExecutionResult result = executeRaw(
            "mutation { insertEmailReply(in: {mailboxRef: \"" + mailbox5 + "\", messageNo: " + msgNo
            + ", subject: \"cross-mailbox\", inReplyTo: \"" + parentInOtherMailbox + "\"})"
            + " { messageNo } }");
        assertThat(result.getErrors())
            .as("disagreeing writers on mailbox_id must surface a value-agreement error")
            .isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("insertEmailReply")).isNull();
        assertThat(dsl.fetchCount(DSL.table("email"),
                DSL.field("mailbox_id", Integer.class).eq(MAILBOX_ALICE)
                    .and(DSL.field("message_no", Integer.class).eq(msgNo))))
            .as("nothing is inserted when the two writers disagree").isZero();
    }

    @Test
    void omittedNullableSelfFk_leavesLoneMailboxDecode_insertsWithNullReplyPointer() {
        // inReplyTo omitted: only mailboxRef writes mailbox_id (=5). The presence guard means no
        // agreement check fires, the row inserts with the decoded mailbox_id, and in_reply_to_no
        // stays NULL (a root, not a reply). MATCH SIMPLE skips the self-FK check on the NULL.
        int msgNo = 102;
        String mailbox5 = NodeIdEncoder.encode("Mailbox", MAILBOX_ALICE);
        Map<String, Object> data = execute(
            "mutation { insertEmailReply(in: {mailboxRef: \"" + mailbox5 + "\", messageNo: " + msgNo
            + ", subject: \"a root\"}) { messageNo inReplyToNo } }");
        Map<String, Object> row = (Map<String, Object>) data.get("insertEmailReply");
        assertThat(row).extractingByKey("messageNo").isEqualTo(msgNo);
        assertThat(row)
            .as("an omitted self-FK leaves in_reply_to_no NULL (lone mailbox decode)")
            .extractingByKey("inReplyToNo").isNull();
    }

    @Test
    void explicitNullNullableSelfFk_insertsInMailboxRefsMailboxWithNullReplyPointer() {
        // inReplyTo sent as an explicit null: the self-FK writes NULL to in_reply_to_no, the column only
        // it writes, and contributes nothing to mailbox_id, which it shares with mailboxRef. The row is
        // (5, n, NULL), the same Email the omitted case returns.
        int msgNo = 103;
        String mailbox5 = NodeIdEncoder.encode("Mailbox", MAILBOX_ALICE);
        Map<String, Object> data = execute(
            "mutation { insertEmailReply(in: {mailboxRef: \"" + mailbox5 + "\", messageNo: " + msgNo
            + ", subject: \"a root\", inReplyTo: null}) { messageNo inReplyToNo } }");
        Map<String, Object> row = (Map<String, Object>) data.get("insertEmailReply");
        assertThat(row).extractingByKey("messageNo").isEqualTo(msgNo);
        assertThat(row)
            .as("an explicit null self-FK returns the same Email as the omitted one")
            .extractingByKey("inReplyToNo").isNull();
        assertThat(storedReplyPointers(msgNo))
            .as("the row lands in mailboxRef's mailbox with a NULL reply pointer")
            .containsExactly(new ReplyRow(MAILBOX_ALICE, msgNo, null));
    }

    @Test
    void wrongTypeInReplyTo_stillThrowsAndInsertsNothing() {
        // A Mailbox id where an Email id belongs is a value that fails to decode, not a missing
        // reference, so it still throws before the INSERT runs. The decode failure reaches the client
        // redacted today, so this pins the refusal and the absent row rather than the message.
        int msgNo = 107;
        String mailbox5 = NodeIdEncoder.encode("Mailbox", MAILBOX_ALICE);
        ExecutionResult result = executeRaw(
            "mutation { insertEmailReply(in: {mailboxRef: \"" + mailbox5 + "\", messageNo: " + msgNo
            + ", inReplyTo: \"" + mailbox5 + "\"}) { messageNo } }");
        assertThat(result.getErrors())
            .as("a wrong-type id on the nullable self-FK must still be rejected")
            .isNotEmpty();
        assertThat((Map<String, Object>) result.getData()).containsEntry("insertEmailReply", null);
        assertThat(storedReplyPointers(msgNo)).as("nothing is inserted").isEmpty();
    }

    @Test
    void bulkInsert_valueExplicitNullAndOmittedSelfFk_eachRowTakesItsOwnMailbox() {
        // One statement, three rows across both seeded mailboxes: a reply in Alice's mailbox, an
        // explicit-null reply pointer in Bob's, and an omitted one in Alice's. mailbox_id always comes
        // from the row's own mailboxRef; in_reply_to_no is the parent's number, then NULL twice.
        String mailbox5 = NodeIdEncoder.encode("Mailbox", MAILBOX_ALICE);
        String mailbox9 = NodeIdEncoder.encode("Mailbox", MAILBOX_BOB);
        String parentEmail = NodeIdEncoder.encode("Email", MAILBOX_ALICE, ROOT_MESSAGE_NO);
        Map<String, Object> data = execute(
            "mutation { insertEmailReplies(in: ["
            + "{mailboxRef: \"" + mailbox5 + "\", messageNo: 104, inReplyTo: \"" + parentEmail + "\"},"
            + "{mailboxRef: \"" + mailbox9 + "\", messageNo: 105, inReplyTo: null},"
            + "{mailboxRef: \"" + mailbox5 + "\", messageNo: 106}"
            + "]) { messageNo inReplyToNo } }");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("insertEmailReplies");
        assertThat(rows).extracting(r -> r.get("messageNo")).containsExactly(104, 105, 106);
        assertThat(storedReplyPointers(104, 105, 106)).containsExactly(
            new ReplyRow(MAILBOX_ALICE, 104, ROOT_MESSAGE_NO),
            new ReplyRow(MAILBOX_BOB, 105, null),
            new ReplyRow(MAILBOX_ALICE, 106, null));
    }

    private record ReplyRow(Integer mailboxId, Integer messageNo, Integer inReplyToNo) {}

    /** The stored {@code email} rows at these message numbers, in message-number order. */
    private List<ReplyRow> storedReplyPointers(Integer... messageNos) {
        var mailboxId = DSL.field("mailbox_id", Integer.class);
        var messageNo = DSL.field("message_no", Integer.class);
        var inReplyToNo = DSL.field("in_reply_to_no", Integer.class);
        return dsl.select(mailboxId, messageNo, inReplyToNo)
            .from(DSL.table("email"))
            .where(messageNo.in(messageNos))
            .orderBy(messageNo)
            .fetch(r -> new ReplyRow(r.value1(), r.value2(), r.value3()));
    }
}
