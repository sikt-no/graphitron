package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_FILTER_ROLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_FILTER_ROLE_RULE;

/**
 * The capture-cadence writer of {@code graphitron_input_field_filter_role}: which rule resolves
 * what one input field contributes at the site that reaches it.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link InputFieldColumnMatches} and {@link NodeIdInstructions}, both of which its
 * ranked arms read, so a stage ahead of either would rank the previous capture's answers.
 */
public final class InputFieldFilterRoles {

    private InputFieldFilterRoles() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_INPUT_FIELD_FILTER_ROLE;
        var rule = GRAPHITRON_INPUT_FIELD_FILTER_ROLE_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .columns(
                target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME,
                target.RESOLVING_SOURCE_NAME, target.RESOLVING_SCHEMA, target.RESOLVING_TABLE,
                target.ROLE, target.AUTHORED_CONDITION, target.SOURCE_NAME, target.SOURCE_LINE,
                target.SOURCE_COLUMN)
            .select(dsl
                .select(
                    rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME, rule.RESOLVING_SOURCE_NAME,
                    rule.RESOLVING_SCHEMA, rule.RESOLVING_TABLE, rule.ROLE, rule.AUTHORED_CONDITION,
                    rule.SOURCE_NAME, rule.SOURCE_LINE, rule.SOURCE_COLUMN)
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
