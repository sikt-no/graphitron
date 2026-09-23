package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_COLUMN_SCOPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_COLUMN_SCOPE_RULE;

/**
 * The capture-cadence writer of {@code graphitron_argument_column_scope}: which table the column
 * name written at an argument's site resolves against: the argument's own navigation, answered at
 * every site where such a name resolves at all.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link ArgumentReferenceStepTargets} and {@link ArgumentScopeTables} in the stratum,
 * and that is a dependency rather than a preference: the rule reads the argument-site walk's
 * terminal and the argument's scope table, so a stage ahead of either would read the previous
 * capture's rows.
 */
public final class ArgumentColumnScopes {

    private ArgumentColumnScopes() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_ARGUMENT_COLUMN_SCOPE;
        var rule = GRAPHITRON_ARGUMENT_COLUMN_SCOPE_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
