package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_CARRIER_ROLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_CARRIER_ROLE_RULE;

/**
 * The capture-cadence writer of {@code graphitron_input_field_carrier_role}: what a column-bearing
 * input field points at, which is what decides whether its value can be compared on the table it
 * was classified against and, where it can, whether those columns are that row's own identity or a
 * pointer at another row.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link InputFieldFilterRoles} and {@link NodeIdDecodeColumns}: the rule reads the
 * filter role to know which sites are column-bearing and the decode column to know where a decoded
 * value lands.
 */
public final class InputFieldCarrierRoles {

    private InputFieldCarrierRoles() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_INPUT_FIELD_CARRIER_ROLE;
        var rule = GRAPHITRON_INPUT_FIELD_CARRIER_ROLE_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
