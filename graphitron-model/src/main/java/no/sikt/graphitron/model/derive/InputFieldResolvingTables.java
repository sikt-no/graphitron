package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_RESOLVING_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_RESOLVING_TABLE_RULE;

/**
 * The capture-cadence writer of {@code graphitron_input_field_resolving_table}: which table an
 * input field is classified against, one row per field per table it is reached under.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>Its position in the stratum is two dependencies rather than a preference: the rule reads
 * {@code graphitron_argument_scope_table}, which {@link ArgumentScopeTables} writes, and the
 * occurrence-path pair, which {@link InputOccurrencePaths} writes.
 */
public final class InputFieldResolvingTables {

    private InputFieldResolvingTables() {}

    /** Reconciles the graph's input-field resolving tables: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_INPUT_FIELD_RESOLVING_TABLE;
        var rule = GRAPHITRON_INPUT_FIELD_RESOLVING_TABLE_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
