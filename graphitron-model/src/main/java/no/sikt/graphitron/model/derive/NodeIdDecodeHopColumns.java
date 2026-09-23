package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_DECODE_HOP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_DECODE_HOP_COLUMN_RULE;

/**
 * The capture-cadence writer of {@code graphitron_node_id_decode_hop_column}: one hop of a decode
 * path oriented along the walk: for each position of the hop's foreign key, the column on the
 * table the hop departs beside the column on the table it arrives at.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link NodeIdDecodeHops}, whose rows it orients column by column, so a stage ahead of
 * it would pair the previous capture's hops.
 */
public final class NodeIdDecodeHopColumns {

    private NodeIdDecodeHopColumns() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_NODE_ID_DECODE_HOP_COLUMN;
        var rule = GRAPHITRON_NODE_ID_DECODE_HOP_COLUMN_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
