package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_DECODE_HOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_DECODE_HOP_RULE;

/**
 * The capture-cadence writer of {@code graphitron_node_id_decode_hop}: the foreign-key hops a
 * decode traverses from the slot's own table to the node type's, one row per hop in authored
 * order.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link NodeIdInstructions}, whose rows are the population it walks, and after both
 * {@code @reference} walk stages, whose terminals are where an authored path departs, so a stage
 * ahead of any of them would walk the previous capture's rows.
 */
public final class NodeIdDecodeHops {

    private NodeIdDecodeHops() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_NODE_ID_DECODE_HOP;
        var rule = GRAPHITRON_NODE_ID_DECODE_HOP_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
