package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Select;

import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_HOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYLESS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESOLVED_TYPE_BINDING;

/**
 * The capture-cadence writer of the two field-site reference-target relations: where each element
 * of a field's {@code @reference} path actually lands, walked from the enclosing type's table
 * binding one element at a time.
 *
 * <p>Recursive because the arms are sequential and nothing else about them is: an element's
 * departure is the previous element's arrival, and only the first element's departure is known
 * without walking, being the type's own binding. A row exists only for an element the chain can be
 * shown to reach, so absence here means "not reached" and never "resolves to nothing in
 * particular".
 *
 * <p>Two relations and not one, for the reason the hop below it is two: the rows have two key
 * shapes, inherited through a recursion that carries the hop's columns forward unchanged. On the
 * {@code KEY} and {@code TABLE} elements the constraint and its orientation are identity; on the
 * other two there is no key to enumerate, so the coordinate with both table triples is already
 * total. The view over the two carries the canonical name every reader spells.
 *
 * <p>The walk itself is {@link ReferenceStepWalk}'s, which is where the recursion, the ranking, the
 * two arities and the hazard that the arities sit a level below the arm filter are all stated. This
 * class is the first of three callers and carries the shallowest coordinate of them: the field's
 * own, with nothing handed down from the seed.
 */
public final class FieldReferenceStepTargets {

    private FieldReferenceStepTargets() {}

    /** The element coordinate this walk is at, ahead of the hop columns every walk carries. */
    private static final List<String> PREFIX =
        List.of("graph_name", "type_name", "field_name", "ordinal");

    /** Reconciles the graph's reference targets: clears both partitions, then re-derives them. */
    public static void derive(DSLContext dsl, String graphName) {
        var keyed = GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYED;
        var keyless = GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYLESS;
        dsl.deleteFrom(keyed).where(keyed.GRAPH_NAME.eq(graphName)).execute();
        dsl.deleteFrom(keyless).where(keyless.GRAPH_NAME.eq(graphName)).execute();
        statements(dsl, graphName).forEach(Query::execute);
    }

    /**
     * The inserts {@link #derive} runs, exposed beside it so an instrument can read what this stage
     * reads off the same object the capture executes. {@code StageOrderGateTest} is that
     * instrument, and without this a stage stated as jOOQ would have a placement nothing checks.
     */
    public static List<Query> statements(DSLContext dsl, String graphName) {
        var keyed = GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYED;
        var keyless = GRAPHITRON_FIELD_REFERENCE_STEP_TARGET_KEYLESS;
        return List.of(
            dsl.insertInto(keyed, List.of(keyed.GRAPH_NAME, keyed.TYPE_NAME, keyed.FIELD_NAME,
                    keyed.ORDINAL, keyed.POSITION, keyed.VIA, keyed.KEY_MATCHED_BY,
                    keyed.FROM_SOURCE_NAME, keyed.FROM_SCHEMA, keyed.FROM_TABLE,
                    keyed.TO_SOURCE_NAME, keyed.TO_SCHEMA, keyed.TO_TABLE,
                    keyed.CONSTRAINT_NAME, keyed.FK_ON_FROM, keyed.TARGETS, keyed.CANDIDATES))
                .select(ReferenceStepWalk.walked(dsl, coordinate(dsl, graphName), true)),
            dsl.insertInto(keyless, List.of(keyless.GRAPH_NAME, keyless.TYPE_NAME,
                    keyless.FIELD_NAME, keyless.ORDINAL, keyless.POSITION, keyless.VIA,
                    keyless.FROM_SOURCE_NAME, keyless.FROM_SCHEMA, keyless.FROM_TABLE,
                    keyless.TO_SOURCE_NAME, keyless.TO_SCHEMA, keyless.TO_TABLE,
                    keyless.TARGETS, keyless.CANDIDATES))
                .select(ReferenceStepWalk.walked(dsl, coordinate(dsl, graphName), false)));
    }

    /** This walk's coordinate: keyed at the field, seeded on the enclosing type's binding. */
    private static ReferenceStepWalk.Coordinate coordinate(DSLContext dsl, String graphName) {
        return new ReferenceStepWalk.Coordinate(PREFIX, List.of(),
            GRAPHITRON_FIELD_REFERENCE_STEP_HOP, seed(dsl, graphName));
    }

    /**
     * The elements at position zero: the hops departing the enclosing type's own table binding,
     * which is the one departure in the chain that is known without walking.
     */
    private static Select<? extends Record> seed(DSLContext dsl, String graphName) {
        var hop = GRAPHITRON_FIELD_REFERENCE_STEP_HOP;
        var bound = GRAPHITRON_RESOLVED_TYPE_BINDING;
        return dsl
            .select(hop.GRAPH_NAME, hop.TYPE_NAME, hop.FIELD_NAME, hop.ORDINAL, hop.POSITION,
                hop.VIA, hop.KEY_MATCHED_BY, hop.FROM_SOURCE_NAME, hop.FROM_SCHEMA, hop.FROM_TABLE,
                hop.TO_SOURCE_NAME, hop.TO_SCHEMA, hop.TO_TABLE, hop.CONSTRAINT_NAME,
                hop.FK_ON_FROM)
            .from(hop)
            .join(bound).on(bound.GRAPH_NAME.eq(hop.GRAPH_NAME))
                .and(bound.TYPE_NAME.eq(hop.TYPE_NAME))
                .and(bound.TABLE_SOURCE_NAME.eq(hop.FROM_SOURCE_NAME))
                .and(bound.TABLE_SCHEMA.eq(hop.FROM_SCHEMA))
                .and(bound.TABLE_NAME.eq(hop.FROM_TABLE))
            .where(hop.POSITION.eq(0))
            .and(hop.GRAPH_NAME.eq(graphName));
    }
}
