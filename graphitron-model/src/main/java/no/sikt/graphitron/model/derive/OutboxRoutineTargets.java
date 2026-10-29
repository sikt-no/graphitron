package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ROUTINE_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ROUTINE_TARGET_RULE;

/**
 * The derivation-stratum writer of {@code graphitron_outbox_routine_target}: the outbox routine the
 * configuration names, resolved against the catalog, and the parameter each role lands in.
 *
 * <p>One statement over the rule view beside the target, as {@link OutboxTargets} is for the table.
 * A configuration that resolves to no one routine whose every parameter answers to a role writes no
 * row, which is what the target defect reads.
 */
public final class OutboxRoutineTargets {

    private OutboxRoutineTargets() {}

    /** Reconciles the graph's outbox routine target: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_OUTBOX_ROUTINE_TARGET;
        var rule = GRAPHITRON_OUTBOX_ROUTINE_TARGET_RULE;

        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .columns(
                target.GRAPH_NAME, target.ROUTINE_SOURCE_NAME, target.ROUTINE_SCHEMA,
                target.ROUTINE_NAME, target.TYPE_POSITION, target.AGGREGATETYPE_POSITION,
                target.AGGREGATEID_POSITION, target.PAYLOAD_POSITION, target.PAYLOAD_KIND)
            .select(dsl
                .select(
                    rule.GRAPH_NAME, rule.ROUTINE_SOURCE_NAME, rule.ROUTINE_SCHEMA,
                    rule.ROUTINE_NAME, rule.TYPE_POSITION, rule.AGGREGATETYPE_POSITION,
                    rule.AGGREGATEID_POSITION, rule.PAYLOAD_POSITION, rule.PAYLOAD_KIND)
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
