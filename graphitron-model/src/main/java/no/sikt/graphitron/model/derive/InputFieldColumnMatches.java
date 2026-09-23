package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_COLUMN_MATCH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_COLUMN_MATCH_RULE;

/**
 * The capture-cadence writer of {@code graphitron_input_field_column_match}: which column an input
 * field's own name resolves to on the table its site navigates to: the column a predicate or an
 * assignment built from that field names.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link InputFieldResolvingTables} and the input-field {@code @reference} walk stage,
 * whose rows its rule resolves an input field's name against, so a stage ahead of either would
 * match against the previous capture's tables.
 */
public final class InputFieldColumnMatches {

    private InputFieldColumnMatches() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_INPUT_FIELD_COLUMN_MATCH;
        var rule = GRAPHITRON_INPUT_FIELD_COLUMN_MATCH_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
