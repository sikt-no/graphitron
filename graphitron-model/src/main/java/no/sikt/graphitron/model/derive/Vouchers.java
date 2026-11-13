package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCEPT_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCEPT_CLAIM_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_CLAIM_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_COMPONENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_COMPONENT_RULE;

/**
 * The voucher stage: each voucher member's column and scalar, each {@code @issue} claim resolved to
 * the member it names and each {@code @accept} claim to the field of its type it names, and the
 * refusals of both markers it can decide this early.
 *
 * <p>Runs as a stage of the graphitron gatherer after {@link Nodes}, since the components read the
 * issuing type's table and a refusal reads the node relation, and long after {@link VoucherMembers},
 * whose membership both read. Clears and re-derives the graph's components and claims on
 * {@link MintedKeys}' terms, the accept claims with them, then writes its codes' rows through {@link EntryDefects#derive} from
 * {@code graphitron_voucher_entry_defect_rule}, which reads what it just wrote, as its last statement.
 */
public final class Vouchers {

    private Vouchers() {}

    /** Writes the graph's voucher components and both markers' claims, then its defects marked with {@code readAt}. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime readAt) {
        var c = GRAPHITRON_VOUCHER_COMPONENT;
        var cr = GRAPHITRON_VOUCHER_COMPONENT_RULE;
        dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graphName)).execute();
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.TYPE_NAME, c.FIELD_NAME, c.TABLE_SOURCE_NAME, c.TABLE_SCHEMA,
                c.TABLE_NAME, c.COLUMN_NAME, c.SCALAR_NAME)
            .select(dsl.select(cr.GRAPH_NAME, cr.TYPE_NAME, cr.FIELD_NAME, cr.TABLE_SOURCE_NAME,
                    cr.TABLE_SCHEMA, cr.TABLE_NAME, cr.COLUMN_NAME, cr.SCALAR_NAME)
                .from(cr)
                .where(cr.GRAPH_NAME.eq(graphName)))
            .execute();
        var v = GRAPHITRON_VOUCHER_CLAIM;
        var vr = GRAPHITRON_VOUCHER_CLAIM_RULE;
        dsl.deleteFrom(v).where(v.GRAPH_NAME.eq(graphName)).execute();
        dsl.insertInto(v)
            .columns(v.GRAPH_NAME, v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN, v.POSITION,
                v.TYPE_NAME, v.FIELD_NAME)
            .select(dsl.select(vr.GRAPH_NAME, vr.SOURCE_NAME, vr.SOURCE_LINE, vr.SOURCE_COLUMN,
                    vr.POSITION, vr.TYPE_NAME, vr.FIELD_NAME)
                .from(vr)
                .where(vr.GRAPH_NAME.eq(graphName)))
            .execute();
        var a = GRAPHITRON_ACCEPT_CLAIM;
        var ar = GRAPHITRON_ACCEPT_CLAIM_RULE;
        dsl.deleteFrom(a).where(a.GRAPH_NAME.eq(graphName)).execute();
        dsl.insertInto(a)
            .columns(a.GRAPH_NAME, a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN, a.POSITION,
                a.TYPE_NAME, a.FIELD_NAME)
            .select(dsl.select(ar.GRAPH_NAME, ar.SOURCE_NAME, ar.SOURCE_LINE, ar.SOURCE_COLUMN,
                    ar.POSITION, ar.TYPE_NAME, ar.FIELD_NAME)
                .from(ar)
                .where(ar.GRAPH_NAME.eq(graphName)))
            .execute();
        EntryDefects.derive(dsl, graphName, readAt, EntryDefects.VOUCHERS);
    }
}
