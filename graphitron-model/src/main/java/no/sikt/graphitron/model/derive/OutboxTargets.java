package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_TARGET_RULE;

/**
 * The derivation-stratum writer of {@code graphitron_outbox_target}: the outbox table the
 * configuration names, resolved against the catalog, and the columns each role lands in.
 *
 * <p>One statement over the rule view beside the target. A configuration that resolves to no one
 * table with the columns a message needs writes no row, which is what the target defect reads.
 */
public final class OutboxTargets {

    private OutboxTargets() {}

    /** Reconciles the graph's outbox target: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_OUTBOX_TARGET;
        var rule = GRAPHITRON_OUTBOX_TARGET_RULE;

        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .columns(
                target.GRAPH_NAME, target.TABLE_SOURCE_NAME, target.TABLE_SCHEMA, target.TABLE_NAME,
                target.TYPE_COLUMN, target.AGGREGATETYPE_COLUMN, target.AGGREGATEID_COLUMN,
                target.PAYLOAD_COLUMN, target.PAYLOAD_KIND)
            .select(dsl
                .select(
                    rule.GRAPH_NAME, rule.TABLE_SOURCE_NAME, rule.TABLE_SCHEMA, rule.TABLE_NAME,
                    rule.TYPE_COLUMN, rule.AGGREGATETYPE_COLUMN, rule.AGGREGATEID_COLUMN,
                    rule.PAYLOAD_COLUMN, rule.PAYLOAD_KIND)
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
