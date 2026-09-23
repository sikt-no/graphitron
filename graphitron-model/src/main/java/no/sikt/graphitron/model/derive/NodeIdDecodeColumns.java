package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_DECODE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_DECODE_COLUMN_RULE;

/**
 * The capture-cadence writer of {@code graphitron_node_id_decode_column}: where each value a
 * decode yields lands: one row per position of the node type's key, carrying that key column and
 * the column on the slot's own table the value lifts back to, or nothing where no such column
 * exists.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link NodeIdDecodeHopColumns} and {@link NodeIdInstructions}. The one stage in the
 * stratum whose rule carries a recursive term, walking the hop columns position by position to
 * carry a local column along the path; the recursion stays in the rule view, where a {@code UNION}
 * reaches its fixpoint on its own, and the stage is the same single statement as every other.
 */
public final class NodeIdDecodeColumns {

    private NodeIdDecodeColumns() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_NODE_ID_DECODE_COLUMN;
        var rule = GRAPHITRON_NODE_ID_DECODE_COLUMN_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
