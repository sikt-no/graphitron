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
 * R354 execution-tier coverage: a self-FK {@code @nodeId @reference} on a Graphitron-owned single-row
 * UPDATE, the UPDATE sibling of {@link SelfFkNodeIdInsertExecutionTest}. Reuses the {@code email} /
 * {@code mailbox} fixture verbatim (no new SQL).
 *
 * <p>{@code UpdateEmailReplyInput} identifies the row by its own {@code id} ({@code @nodeId}, own-PK
 * short-circuit → WHERE {@code (mailbox_id, message_no)}) and repoints its parent through the self-FK
 * {@code inReplyTo} ({@code @nodeId(typeName: "Email") @reference}, whose {@code email_in_reply_to_fk}
 * child columns {@code (mailbox_id, in_reply_to_no)} route wholly to SET). {@code mailbox_id} is then
 * both a WHERE column (from {@code id}) and a SET column (from {@code inReplyTo}); the generated fetcher
 * emits a cross-partition {@code requireColumnAgreement} check before the UPDATE.
 *
 * <p>Three cases, matching the spec's matrix: the two sides <b>agree</b> on {@code mailbox_id} (the
 * repoint of {@code in_reply_to_no} lands and the {@code mailbox_id} SET is a no-op); they
 * <b>disagree</b> (parent in a different mailbox) and {@code requireColumnAgreement} throws before the
 * UPDATE, so nothing moves; and an <b>omitted</b> nullable {@code inReplyTo} updates {@code subject}
 * only, with the presence guard meaning no agreement check fires.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class SelfFkNodeIdUpdateExecutionTest {

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
    void cleanUpUpdatedRows() {
        // Each test owns a distinct message_no >= 100 in alice's mailbox; drop them so cleanup is
        // scoped and never touches the seeded roots.
        dsl.deleteFrom(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_ALICE))
            .and(DSL.field("message_no", Integer.class).ge(100))
            .execute();
    }

    /** Inserts a reply-to-be at (alice, msgNo) with a NULL parent pointer and the given subject. */
    private void seedRow(int msgNo, String subject) {
        dsl.insertInto(DSL.table("email"),
                DSL.field("mailbox_id"), DSL.field("message_no"), DSL.field("subject"))
            .values(MAILBOX_ALICE, msgNo, subject)
            .execute();
    }

    private Integer inReplyToNoOf(int msgNo) {
        return dsl.select(DSL.field("in_reply_to_no", Integer.class))
            .from(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_ALICE))
            .and(DSL.field("message_no", Integer.class).eq(msgNo))
            .fetchOne(DSL.field("in_reply_to_no", Integer.class));
    }

    private String subjectOf(int msgNo) {
        return dsl.select(DSL.field("subject", String.class))
            .from(DSL.table("email"))
            .where(DSL.field("mailbox_id", Integer.class).eq(MAILBOX_ALICE))
            .and(DSL.field("message_no", Integer.class).eq(msgNo))
            .fetchOne(DSL.field("subject", String.class));
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
    void agreeOnSharedMailboxColumn_repointsInReplyTo_mailboxIdSetIsNoOp() {
        // id -> Email(5, 200) identifies the row → WHERE mailbox_id=5, message_no=200. inReplyTo ->
        // Email(5, 1) decodes the self-FK child columns (mailbox_id=5, in_reply_to_no=1) → SET. The two
        // sides agree on mailbox_id (5 == 5): the agreement passes, in_reply_to_no is repointed to 1,
        // and the mailbox_id SET write is provably a no-op.
        int msgNo = 200;
        seedRow(msgNo, "before");
        String self = NodeIdEncoder.encode("Email", MAILBOX_ALICE, msgNo);
        String parent = NodeIdEncoder.encode("Email", MAILBOX_ALICE, ROOT_MESSAGE_NO);
        Map<String, Object> data = execute(
            "mutation { updateEmailReply(in: {id: \"" + self + "\", inReplyTo: \"" + parent
            + "\", subject: \"re: root\"}) { messageNo inReplyToNo subject } }");
        Map<String, Object> row = (Map<String, Object>) data.get("updateEmailReply");
        assertThat(row).extractingByKey("messageNo").isEqualTo(msgNo);
        assertThat(row)
            .as("the self-FK SET repoints in_reply_to_no to the decoded parent message_no")
            .extractingByKey("inReplyToNo").isEqualTo(ROOT_MESSAGE_NO);
        assertThat(row).extractingByKey("subject").isEqualTo("re: root");
        // The row stayed in mailbox 5 (the mailbox_id SET was a no-op, not a row move).
        assertThat(inReplyToNoOf(msgNo)).isEqualTo(ROOT_MESSAGE_NO);
    }

    @Test
    void disagreeOnSharedMailboxColumn_throwsAndUpdatesNothing() {
        // id -> Email(5, 201) → WHERE mailbox_id=5. inReplyTo -> Email(9, 1) decodes a parent in a
        // DIFFERENT mailbox, so the self-FK SET wants mailbox_id=9. The two sides disagree on mailbox_id
        // (5 vs 9): requireColumnAgreement throws before the UPDATE — the row is not moved and
        // in_reply_to_no stays NULL (no silent row-move via a second WHERE predicate).
        int msgNo = 201;
        seedRow(msgNo, "before");
        String self = NodeIdEncoder.encode("Email", MAILBOX_ALICE, msgNo);
        String parentOtherMailbox = NodeIdEncoder.encode("Email", MAILBOX_BOB, ROOT_MESSAGE_NO);
        ExecutionResult result = executeRaw(
            "mutation { updateEmailReply(in: {id: \"" + self + "\", inReplyTo: \"" + parentOtherMailbox
            + "\", subject: \"cross-mailbox\"}) { messageNo } }");
        assertThat(result.getErrors())
            .as("disagreeing WHERE/SET writers on mailbox_id must surface a value-agreement error")
            .isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("updateEmailReply")).isNull();
        assertThat(inReplyToNoOf(msgNo))
            .as("nothing is updated when the two sides disagree").isNull();
        assertThat(subjectOf(msgNo))
            .as("the subject SET is rolled back with the agreement throw").isEqualTo("before");
    }

    @Test
    void omittedNullableSelfFk_updatesSubjectOnly_noAgreementCheck() {
        // inReplyTo omitted: SET is subject only, the self-FK contributes nothing. The presence guard
        // means no cross-partition agreement check fires, in_reply_to_no stays NULL, and subject updates.
        int msgNo = 202;
        seedRow(msgNo, "before");
        String self = NodeIdEncoder.encode("Email", MAILBOX_ALICE, msgNo);
        Map<String, Object> data = execute(
            "mutation { updateEmailReply(in: {id: \"" + self + "\", subject: \"updated\"})"
            + " { messageNo inReplyToNo subject } }");
        Map<String, Object> row = (Map<String, Object>) data.get("updateEmailReply");
        assertThat(row).extractingByKey("messageNo").isEqualTo(msgNo);
        assertThat(row)
            .as("an omitted self-FK leaves in_reply_to_no untouched (still NULL)")
            .extractingByKey("inReplyToNo").isNull();
        assertThat(row).extractingByKey("subject").isEqualTo("updated");
    }
}
