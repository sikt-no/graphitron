package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_MEMBER_RULE;

/**
 * The capture-cadence writer of {@code graphitron_voucher_member}: the field declarations each
 * {@code @issue} absorbs into its voucher, from the rule view beside it.
 *
 * <p>Runs as a stage of the graphitron gatherer right after the anchor and before macro expansion,
 * whose authored element sets anti-join what it writes, so an absorbed field is never part of the
 * emitted population. It reads the transcription alone, which is all that exists that early, and it
 * decides membership independently of every refusal, so a refused {@code @issue} still withholds the
 * fields it absorbs. Clears and re-derives the graph's rows on {@link MintedKeys}' terms.
 */
public final class VoucherMembers {

    private VoucherMembers() {}

    /** Clears and re-derives the graph's voucher members; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        var m = GRAPHITRON_VOUCHER_MEMBER;
        var r = GRAPHITRON_VOUCHER_MEMBER_RULE;
        dsl.deleteFrom(m).where(m.GRAPH_NAME.eq(graphName)).execute();
        dsl.insertInto(m)
            .columns(m.GRAPH_NAME, m.TYPE_NAME, m.FIELD_NAME, m.ISSUING_FIELD)
            .select(dsl.select(r.GRAPH_NAME, r.TYPE_NAME, r.FIELD_NAME, r.ISSUING_FIELD)
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
