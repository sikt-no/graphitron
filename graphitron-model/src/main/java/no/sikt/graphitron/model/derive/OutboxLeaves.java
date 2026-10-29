package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_LEAF;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_LEAF_RULE;

/**
 * The derivation-stratum writer of {@code graphitron_outbox_leaf}: the keys of every {@code @outbox}
 * message's payload, by the source each reads.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, where it can be read beside the relations it joins: the captured
 * mapping pairs, the configured common payload, the field's arguments through
 * {@code graphitron_argmapping_candidate}, the written table and the columns and node ids of the
 * type the field returns.
 *
 * <p>Its position is two dependencies: the rule reads {@code graphitron_argmapping_candidate},
 * which {@code ArgMappingCandidates} writes, and the entry defects read this relation, so it runs
 * between them.
 */
public final class OutboxLeaves {

    private OutboxLeaves() {}

    /** Reconciles the graph's outbox leaves: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_OUTBOX_LEAF;
        var rule = GRAPHITRON_OUTBOX_LEAF_RULE;

        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .columns(
                target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME, target.ORDINAL,
                target.KEY_NAME, target.ORIGIN, target.POSITION, target.SOURCE_KIND,
                target.SOURCE_PATH, target.COLUMN_NAME, target.NODE_TYPE_NAME)
            .select(dsl
                .select(
                    rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME, rule.ORDINAL, rule.KEY_NAME,
                    rule.ORIGIN, rule.POSITION, rule.SOURCE_KIND, rule.SOURCE_PATH,
                    rule.COLUMN_NAME, rule.NODE_TYPE_NAME)
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
