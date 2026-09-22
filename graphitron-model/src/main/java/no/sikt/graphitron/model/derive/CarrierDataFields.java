package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CARRIER_DATA_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CARRIER_DATA_FIELD_RULE;

/**
 * The capture-cadence writer of {@code graphitron_carrier_data_field}: where a mutation payload's
 * data arrives, one row per data channel per producing family.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>Runs in the derivation stratum after the hand-written producers, and that is a position its
 * read set earns rather than a preference: the rule reaches {@code intent_type_backing_class}
 * through {@code intent_type_backing}, and {@link TypeBackingRows} is what writes it, so a stage
 * ahead of that producer would read the previous capture's rows.
 */
public final class CarrierDataFields {

    private CarrierDataFields() {}

    /** Reconciles the graph's carrier data channels: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_CARRIER_DATA_FIELD;
        var rule = GRAPHITRON_CARRIER_DATA_FIELD_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
