package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SCOPE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SCOPE_TABLE_RULE;

/**
 * The capture-cadence writer of {@code graphitron_field_scope_table}: which table a field's own
 * generated SQL binds against, one row per table where several answer.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link CarrierDataFields} in the stratum, and that is a dependency rather than a
 * preference: the payload rung reads {@code graphitron_carrier_data_field}, so a stage ahead of the
 * one that writes it would read the previous capture's rows.
 */
public final class FieldScopeTables {

    private FieldScopeTables() {}

    /** Reconciles the graph's field-site scope tables: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_FIELD_SCOPE_TABLE;
        var rule = GRAPHITRON_FIELD_SCOPE_TABLE_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
