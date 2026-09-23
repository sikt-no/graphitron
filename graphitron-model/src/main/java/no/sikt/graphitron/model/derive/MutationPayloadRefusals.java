package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_PAYLOAD_REFUSAL;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_PAYLOAD_REFUSAL_RULE;

/**
 * The capture-cadence writer of {@code graphitron_mutation_payload_refusal}: why a walker-driven
 * write refuses one input field, located at the occurrence that reaches it.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link MutationWritePayloads}, {@link InputFieldFilterRoles} and {@link
 * InputFieldCarrierRoles}, whose rows are the write surface it walks and the roles it refuses on.
 */
public final class MutationPayloadRefusals {

    private MutationPayloadRefusals() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_MUTATION_PAYLOAD_REFUSAL;
        var rule = GRAPHITRON_MUTATION_PAYLOAD_REFUSAL_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
