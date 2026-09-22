package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Select;

import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_STEP_TARGET_KEYED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_STEP_TARGET_KEYLESS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_SCOPE_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_REFERENCE_STEP_HOP;

/**
 * The capture-cadence writer of the two argument-site reference-target relations: where each
 * element of an argument's {@code @reference} path lands, walked from the table the argument's own
 * content binds against.
 *
 * <p>Two relations and not one, for the reason the field-site pair is two: the rows carry two key
 * shapes. On a {@code KEY} or {@code TABLE} element the constraint and its orientation are
 * identity; on a {@code NAME_MATCH} or {@code CONDITION} element there is no key to enumerate, so
 * the coordinate with both table triples is already total. One relation over all four arms would
 * declare no key at all, and a keyless stage doubles its rows on a second capture of one graph
 * without failing.
 *
 * <p>The key is not the field walk's. It carries {@code argument_name}, because an argument's path
 * departs from what the argument's own content binds against rather than from the enclosing type's
 * binding, so two arguments of one field walk two chains from one authored path.
 *
 * <p>The walk itself is {@link ReferenceStepWalk}'s, which is where the recursion, the ranking, the
 * two arities and the hazard that the arities sit a level below the arm filter are all stated.
 */
public final class ArgumentReferenceStepTargets {

    private ArgumentReferenceStepTargets() {}

    /** The element coordinate this walk is at, ahead of the hop columns every walk carries. */
    private static final List<String> PREFIX =
        List.of("graph_name", "type_name", "field_name", "argument_name", "ordinal");

    /** Reconciles the graph's argument-site reference targets: clears both arms, then re-derives. */
    public static void derive(DSLContext dsl, String graphName) {
        var keyed = GRAPHITRON_ARGUMENT_REFERENCE_STEP_TARGET_KEYED;
        var keyless = GRAPHITRON_ARGUMENT_REFERENCE_STEP_TARGET_KEYLESS;
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
        var keyed = GRAPHITRON_ARGUMENT_REFERENCE_STEP_TARGET_KEYED;
        var keyless = GRAPHITRON_ARGUMENT_REFERENCE_STEP_TARGET_KEYLESS;
        return List.of(
            dsl.insertInto(keyed, List.of(keyed.GRAPH_NAME, keyed.TYPE_NAME, keyed.FIELD_NAME,
                    keyed.ARGUMENT_NAME, keyed.ORDINAL, keyed.POSITION, keyed.VIA,
                    keyed.KEY_MATCHED_BY, keyed.FROM_SOURCE_NAME, keyed.FROM_SCHEMA,
                    keyed.FROM_TABLE, keyed.TO_SOURCE_NAME, keyed.TO_SCHEMA, keyed.TO_TABLE,
                    keyed.CONSTRAINT_NAME, keyed.FK_ON_FROM, keyed.TARGETS, keyed.CANDIDATES))
                .select(ReferenceStepWalk.walked(dsl, coordinate(dsl, graphName), true)),
            dsl.insertInto(keyless, List.of(keyless.GRAPH_NAME, keyless.TYPE_NAME,
                    keyless.FIELD_NAME, keyless.ARGUMENT_NAME, keyless.ORDINAL, keyless.POSITION,
                    keyless.VIA, keyless.FROM_SOURCE_NAME, keyless.FROM_SCHEMA,
                    keyless.FROM_TABLE, keyless.TO_SOURCE_NAME, keyless.TO_SCHEMA,
                    keyless.TO_TABLE, keyless.TARGETS, keyless.CANDIDATES))
                .select(ReferenceStepWalk.walked(dsl, coordinate(dsl, graphName), false)));
    }

    /** This walk's coordinate: keyed at the argument, seeded on the argument's own departure. */
    private static ReferenceStepWalk.Coordinate coordinate(DSLContext dsl, String graphName) {
        return new ReferenceStepWalk.Coordinate(PREFIX, List.of(),
            INTENT_ARGUMENT_REFERENCE_STEP_HOP, seed(dsl, graphName));
    }

    /**
     * The elements at position zero: the hops departing a table the argument's own content binds
     * against, which is what makes this walk the argument's rather than its field's.
     */
    private static Select<? extends Record> seed(DSLContext dsl, String graphName) {
        var hop = INTENT_ARGUMENT_REFERENCE_STEP_HOP;
        var scope = GRAPHITRON_ARGUMENT_SCOPE_TABLE;
        return dsl
            .select(hop.GRAPH_NAME, hop.TYPE_NAME, hop.FIELD_NAME, hop.ARGUMENT_NAME, hop.ORDINAL,
                hop.POSITION, hop.VIA, hop.KEY_MATCHED_BY,
                hop.FROM_SOURCE_NAME, hop.FROM_SCHEMA, hop.FROM_TABLE,
                hop.TO_SOURCE_NAME, hop.TO_SCHEMA, hop.TO_TABLE, hop.CONSTRAINT_NAME,
                hop.FK_ON_FROM)
            .from(hop)
            .join(scope).on(scope.GRAPH_NAME.eq(hop.GRAPH_NAME))
                .and(scope.TYPE_NAME.eq(hop.TYPE_NAME))
                .and(scope.FIELD_NAME.eq(hop.FIELD_NAME))
                .and(scope.ARGUMENT_NAME.eq(hop.ARGUMENT_NAME))
                .and(scope.TABLE_SOURCE_NAME.eq(hop.FROM_SOURCE_NAME))
                .and(scope.TABLE_SCHEMA.eq(hop.FROM_SCHEMA))
                .and(scope.TABLE_NAME.eq(hop.FROM_TABLE))
            .where(hop.POSITION.eq(0))
            .and(hop.GRAPH_NAME.eq(graphName));
    }
}
