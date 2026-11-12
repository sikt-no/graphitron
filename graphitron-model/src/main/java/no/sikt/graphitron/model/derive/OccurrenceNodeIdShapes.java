package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_node_id_shape}: how each node id lands in the class it is
 * handed to, where a shape fits, from the rule view beside it, marked and swept.
 *
 * <p>A step of {@link OccurrenceBuilds}' stage, after {@link OccurrenceNodeIdSlots}, whose landings it
 * judges.
 */
public final class OccurrenceNodeIdShapes {

    private OccurrenceNodeIdShapes() {}

    /** States the shape of every node id at every slot it fits. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE;
        var r = GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE_RULE;
        dsl.insertInto(d)
            .columns(d.GRAPH_NAME, d.COORDINATE, d.SLOT, d.BOUND, d.DECODE_SHAPE, d.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.SLOT, r.BOUND, r.DECODE_SHAPE,
                    val(touchedAt, d.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(d.GRAPH_NAME, d.COORDINATE, d.SLOT, d.BOUND)
            .doUpdate()
            .set(d.DECODE_SHAPE, excluded(d.DECODE_SHAPE))
            .set(d.TOUCHED_AT, excluded(d.TOUCHED_AT))
            .execute();
    }

    /** Deletes the shapes this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE;
        dsl.deleteFrom(d)
            .where(d.GRAPH_NAME.eq(graph))
            .and(d.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
