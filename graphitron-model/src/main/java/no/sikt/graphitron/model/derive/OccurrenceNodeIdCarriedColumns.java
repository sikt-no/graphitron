package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_CARRIED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_CARRIED_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_node_id_carried}: the column on the table a node id's path
 * departs that each key position is carried back to by the path's one foreign key, from its rule
 * view, marked and swept.
 *
 * <p>A step of {@link OccurrenceBindings}, after {@link OccurrenceNodeIds}, whose key columns it
 * pairs with the join path steps {@link OccurrenceDepths} wrote.
 */
public final class OccurrenceNodeIdCarriedColumns {

    private OccurrenceNodeIdCarriedColumns() {}

    /** States the graph's carried key columns. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_NODE_ID_CARRIED;
        var r = GRAPHITRON_OCCURRENCE_NODE_ID_CARRIED_RULE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.BOUND, c.POSITION, c.TABLE_SOURCE_NAME, c.TABLE_SCHEMA,
                c.TABLE_NAME, c.COLUMN_NAME, c.TOUCHED_AT)
            .select(select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.POSITION, r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA,
                    r.TABLE_NAME, r.COLUMN_NAME, val(touchedAt, c.TOUCHED_AT))
                .from(r).where(r.GRAPH_NAME.eq(graph)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.BOUND, c.POSITION)
            .doUpdate()
            .set(c.TABLE_SOURCE_NAME, excluded(c.TABLE_SOURCE_NAME))
            .set(c.TABLE_SCHEMA, excluded(c.TABLE_SCHEMA))
            .set(c.TABLE_NAME, excluded(c.TABLE_NAME))
            .set(c.COLUMN_NAME, excluded(c.COLUMN_NAME))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .execute();
    }

    /** Deletes the carried key columns this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_NODE_ID_CARRIED;
        dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graph), c.TOUCHED_AT.ne(touchedAt)).execute();
    }
}
