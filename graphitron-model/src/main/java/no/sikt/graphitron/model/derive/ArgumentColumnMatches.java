package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_COLUMN_MATCH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_COLUMN_MATCH_RULE;

/**
 * The capture-cadence writer of {@code graphitron_argument_column_match}: which column an
 * argument's own name resolves to on the table its site navigates to: the column a filter
 * predicate built from that argument compares against.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link ArgumentColumnScopes}, whose rows its rule resolves names against, so a stage
 * ahead of it would match against the previous capture's scope.
 */
public final class ArgumentColumnMatches {

    private ArgumentColumnMatches() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_ARGUMENT_COLUMN_MATCH;
        var rule = GRAPHITRON_ARGUMENT_COLUMN_MATCH_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
