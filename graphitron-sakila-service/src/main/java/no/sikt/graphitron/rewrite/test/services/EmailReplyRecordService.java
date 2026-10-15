package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.EmailRecord;

/**
 * Execution-tier fixture: a jOOQ {@link EmailRecord} bound directly as a {@code @service} input param,
 * built from an input whose two {@code @nodeId} references share {@code mailbox_id}: the cross-table
 * {@code Mailbox} reference writes {@code mailbox_id}, and the self-FK {@code Email} reference writes
 * {@code (mailbox_id, in_reply_to_no)}. The generated helper checks the two agree on {@code mailbox_id}
 * before loading the record.
 *
 * <p>{@link #describeReply} reports the two columns off the constructed record without touching the
 * database, in the manner of {@link StorageBinRecordAgreementService}: the agreement and the loads both
 * happen inside the helper, so the record itself is the observation.
 */
public final class EmailReplyRecordService {

    private EmailReplyRecordService() {}

    /** Reports {@code mailbox_id} and {@code in_reply_to_no} off the record the helper constructed. */
    public static String describeReply(EmailRecord in) {
        return "mailbox_id=" + in.getMailboxId() + ", in_reply_to_no=" + in.getInReplyToNo();
    }
}
