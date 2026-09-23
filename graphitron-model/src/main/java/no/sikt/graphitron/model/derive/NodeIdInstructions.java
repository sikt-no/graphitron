package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_INSTRUCTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_INSTRUCTION_RULE;

/**
 * The capture-cadence writer of {@code graphitron_node_id_instruction}: every slot carrying the
 * @nodeId instruction, and which type it names.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link ArgumentScopeTables} and both {@code @reference} walk stages in the stratum, and
 * that is a dependency rather than a preference: the rule reads the argument's scope table and the
 * argument-site walk's terminal, so a stage ahead of either would read the previous capture's
 * rows.
 */
public final class NodeIdInstructions {

    private NodeIdInstructions() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_NODE_ID_INSTRUCTION;
        var rule = GRAPHITRON_NODE_ID_INSTRUCTION_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
