package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R328 execution-tier coverage: a self-FK {@code @nodeId @reference} on a Graphitron-owned INSERT,
 * the neutral {@code email} form of the CAMPUS self-FK case, proven end to end through R322's
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
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class SelfFkNodeIdInsertExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    // Seeded in init.sql: mailbox 5 (alice) + root email (5, 1); mailbox 9 (bob) + root email (9, 1).
    private static final int MAILBOX_ALICE = 5;
    private static final int MAILBOX_BOB = 9;
    private static final int ROOT_MESSAGE_NO = 1;

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
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @AfterEach
    void cleanUpInsertedReplies() {
        // Drop everything below the seeded root message_no in alice's mailbox; each test inserts a
        // reply at a distinct message_no >= 100 so cleanup is mailbox-scoped and never touches seeds.
        dsl.deleteFrom(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_ALICE))
            .and(DSL.field("message_no", Integer.class).ge(100))
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
}
