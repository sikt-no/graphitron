package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_PAYLOAD_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_PAYLOAD_COLUMN_RULE;

/**
 * The capture-cadence writer of {@code graphitron_mutation_payload_column}: what a write payload
 * actually puts on the table, column by column: one row per admitted occurrence per decode slot,
 * carrying the column that slot binds and what the carrier at the end of it points at.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link MutationPayloadRefusals}, which it reads to admit an occurrence, and after the
 * input-field roles and {@link NodeIdDecodeColumns}, which say what each admitted occurrence puts
 * on the table.
 */
public final class MutationPayloadColumns {

    private MutationPayloadColumns() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_MUTATION_PAYLOAD_COLUMN;
        var rule = GRAPHITRON_MUTATION_PAYLOAD_COLUMN_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
