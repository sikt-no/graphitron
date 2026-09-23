package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_PAYLOAD_KEY_MEMBERSHIP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_PAYLOAD_KEY_MEMBERSHIP_RULE;

/**
 * The capture-cadence writer of {@code graphitron_mutation_payload_key_membership}: which of the
 * columns an UPDATE's payload contributes fall inside the key it matched, and how each carrier as
 * a whole falls against that boundary.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link MutationPayloadColumns}, whose columns it measures against the key the payload
 * matched.
 */
public final class MutationPayloadKeyMemberships {

    private MutationPayloadKeyMemberships() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_MUTATION_PAYLOAD_KEY_MEMBERSHIP;
        var rule = GRAPHITRON_MUTATION_PAYLOAD_KEY_MEMBERSHIP_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
