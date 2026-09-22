package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Select;

import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_HOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET_KEYED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET_KEYLESS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_RESOLVING_TABLE;

/**
 * The capture-cadence writer of the two input-field reference-target relations: where each element
 * of an input field's {@code @reference} path lands, walked from the table that input field is
 * classified against.
 *
 * <p>Two relations and not one, on the field-site pair's terms: the rows carry two key shapes, and
 * one relation over all four arms would declare no key at all.
 *
 * <p>The key is neither sibling's. The whole departure triple is in it, because an input field's
 * departure is its consuming site's and not its own: one input field reached under two arguments
 * whose fields select from different tables walks two chains from one authored path, and a key
 * transcribed from the field walk would keep one of them.
 *
 * <p>The walk itself is {@link ReferenceStepWalk}'s, which is where the recursion, the ranking, the
 * two arities and the hazard that the arities sit a level below the arm filter are all stated. The
 * resolving triple is carried rather than joined, the hop this walk recurses over being the
 * field-site one, which does not know it.
 */
public final class InputFieldReferenceStepTargets {

    private InputFieldReferenceStepTargets() {}

    /** The element coordinate this walk is at, ahead of the hop columns every walk carries. */
    private static final List<String> PREFIX = List.of("graph_name", "type_name", "field_name",
        "resolving_source_name", "resolving_schema", "resolving_table", "ordinal");

    /** The departure the seed resolved, handed down the walk rather than read off each hop. */
    private static final List<String> CARRIED =
        List.of("resolving_source_name", "resolving_schema", "resolving_table");

    /** Reconciles the graph's input-field reference targets: clears both arms, then re-derives. */
    public static void derive(DSLContext dsl, String graphName) {
        var keyed = GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET_KEYED;
        var keyless = GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET_KEYLESS;
        dsl.deleteFrom(keyed).where(keyed.GRAPH_NAME.eq(graphName)).execute();
        dsl.deleteFrom(keyless).where(keyless.GRAPH_NAME.eq(graphName)).execute();
        statements(dsl, graphName).forEach(Query::execute);
    }

    /**
     * The inserts {@link #derive} runs, exposed beside it so an instrument can read what this stage
     * reads off the same object the capture executes.
     */
    public static List<Query> statements(DSLContext dsl, String graphName) {
        var keyed = GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET_KEYED;
        var keyless = GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET_KEYLESS;
        return List.of(
            dsl.insertInto(keyed, List.of(keyed.GRAPH_NAME, keyed.TYPE_NAME, keyed.FIELD_NAME,
                    keyed.RESOLVING_SOURCE_NAME, keyed.RESOLVING_SCHEMA, keyed.RESOLVING_TABLE,
                    keyed.ORDINAL, keyed.POSITION, keyed.VIA, keyed.KEY_MATCHED_BY,
                    keyed.FROM_SOURCE_NAME, keyed.FROM_SCHEMA, keyed.FROM_TABLE,
                    keyed.TO_SOURCE_NAME, keyed.TO_SCHEMA, keyed.TO_TABLE,
                    keyed.CONSTRAINT_NAME, keyed.FK_ON_FROM, keyed.TARGETS, keyed.CANDIDATES))
                .select(ReferenceStepWalk.walked(dsl, coordinate(dsl, graphName), true)),
            dsl.insertInto(keyless, List.of(keyless.GRAPH_NAME, keyless.TYPE_NAME,
                    keyless.FIELD_NAME, keyless.RESOLVING_SOURCE_NAME, keyless.RESOLVING_SCHEMA,
                    keyless.RESOLVING_TABLE, keyless.ORDINAL, keyless.POSITION, keyless.VIA,
                    keyless.FROM_SOURCE_NAME, keyless.FROM_SCHEMA, keyless.FROM_TABLE,
                    keyless.TO_SOURCE_NAME, keyless.TO_SCHEMA, keyless.TO_TABLE,
                    keyless.TARGETS, keyless.CANDIDATES))
                .select(ReferenceStepWalk.walked(dsl, coordinate(dsl, graphName), false)));
    }

    /** This walk's coordinate: keyed at the input field and its resolving table together. */
    private static ReferenceStepWalk.Coordinate coordinate(DSLContext dsl, String graphName) {
        return new ReferenceStepWalk.Coordinate(PREFIX, CARRIED,
            GRAPHITRON_FIELD_REFERENCE_STEP_HOP, seed(dsl, graphName));
    }

    /**
     * The elements at position zero: the hops departing a table the input field is classified
     * against, one chain per resolving table the field is reached under.
     */
    private static Select<? extends Record> seed(DSLContext dsl, String graphName) {
        var hop = GRAPHITRON_FIELD_REFERENCE_STEP_HOP;
        var resolving = GRAPHITRON_INPUT_FIELD_RESOLVING_TABLE;
        return dsl
            .select(hop.GRAPH_NAME, hop.TYPE_NAME, hop.FIELD_NAME,
                resolving.TABLE_SOURCE_NAME.as("resolving_source_name"),
                resolving.TABLE_SCHEMA.as("resolving_schema"),
                resolving.TABLE_NAME.as("resolving_table"),
                hop.ORDINAL, hop.POSITION, hop.VIA, hop.KEY_MATCHED_BY,
                hop.FROM_SOURCE_NAME, hop.FROM_SCHEMA, hop.FROM_TABLE,
                hop.TO_SOURCE_NAME, hop.TO_SCHEMA, hop.TO_TABLE, hop.CONSTRAINT_NAME,
                hop.FK_ON_FROM)
            .from(hop)
            .join(resolving).on(resolving.GRAPH_NAME.eq(hop.GRAPH_NAME))
                .and(resolving.TYPE_NAME.eq(hop.TYPE_NAME))
                .and(resolving.FIELD_NAME.eq(hop.FIELD_NAME))
                .and(resolving.TABLE_SOURCE_NAME.eq(hop.FROM_SOURCE_NAME))
                .and(resolving.TABLE_SCHEMA.eq(hop.FROM_SCHEMA))
                .and(resolving.TABLE_NAME.eq(hop.FROM_TABLE))
            .where(hop.POSITION.eq(0))
            .and(hop.GRAPH_NAME.eq(graphName));
    }
}
