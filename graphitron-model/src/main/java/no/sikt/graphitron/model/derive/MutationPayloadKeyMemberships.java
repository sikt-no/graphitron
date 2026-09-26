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
            .columns(
                target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME, target.PATH,
                target.CONTAINER_TYPE_NAME, target.INPUT_FIELD_NAME, target.ROLE,
                target.CARRIER_ROLE, target.NON_NULL, target.POSITION, target.COLUMN_NAME,
                target.IN_KEY, target.CARRIER_KEY_MEMBERSHIP, target.CONSTRAINT_NAME,
                target.WRITE_SOURCE_NAME, target.WRITE_SCHEMA, target.WRITE_TABLE,
                target.SOURCE_NAME, target.SOURCE_LINE, target.SOURCE_COLUMN)
            .select(dsl
                .select(
                    rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME, rule.PATH,
                    rule.CONTAINER_TYPE_NAME, rule.INPUT_FIELD_NAME, rule.ROLE, rule.CARRIER_ROLE,
                    rule.NON_NULL, rule.POSITION, rule.COLUMN_NAME, rule.IN_KEY,
                    rule.CARRIER_KEY_MEMBERSHIP, rule.CONSTRAINT_NAME, rule.WRITE_SOURCE_NAME,
                    rule.WRITE_SCHEMA, rule.WRITE_TABLE, rule.SOURCE_NAME, rule.SOURCE_LINE,
                    rule.SOURCE_COLUMN)
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
