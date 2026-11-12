package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_node_id}: the key columns each node id at an occurrence packs
 * or unpacks, from the rule view beside it, under a {@code NODE_ID} row of
 * {@code graphitron_occurrence_binding}, marked and swept.
 *
 * <p>A step of {@link OccurrenceBindings}, after {@link OccurrenceDepths} has written the needs and
 * join paths whose landings the inferences read, and before the columns, which skip the occurrences
 * it binds; the binding claims, whose written node types it reads, are written before both. The sweep
 * deletes the stale {@code NODE_ID} bindings, the subtype rows under them going by the cascade, and
 * then the stale positions under a binding that still stands, which a node whose key lost a column
 * leaves behind.
 */
public final class OccurrenceNodeIds {

    private OccurrenceNodeIds() {}

    /** States the node id bindings of the occurrences. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var r = GRAPHITRON_OCCURRENCE_NODE_ID_RULE;
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl
                .selectDistinct(r.GRAPH_NAME, r.COORDINATE, r.BOUND, inline("NODE_ID"),
                    val(touchedAt, b.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();
        dsl.insertInto(n)
            .columns(n.GRAPH_NAME, n.COORDINATE, n.BOUND, n.POSITION, n.KIND, n.BASIS,
                n.NODE_TYPE_NAME, n.TABLE_SOURCE_NAME, n.TABLE_SCHEMA, n.TABLE_NAME, n.COLUMN_NAME,
                n.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.POSITION, inline("NODE_ID"),
                    r.BASIS, r.NODE_TYPE_NAME, r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME,
                    r.COLUMN_NAME, val(touchedAt, n.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(n.GRAPH_NAME, n.COORDINATE, n.BOUND, n.POSITION)
            .doUpdate()
            .set(n.BASIS, excluded(n.BASIS))
            .set(n.NODE_TYPE_NAME, excluded(n.NODE_TYPE_NAME))
            .set(n.TABLE_SOURCE_NAME, excluded(n.TABLE_SOURCE_NAME))
            .set(n.TABLE_SCHEMA, excluded(n.TABLE_SCHEMA))
            .set(n.TABLE_NAME, excluded(n.TABLE_NAME))
            .set(n.COLUMN_NAME, excluded(n.COLUMN_NAME))
            .set(n.TOUCHED_AT, excluded(n.TOUCHED_AT))
            .execute();
    }

    /** Deletes the NODE_ID bindings this reading did not mark, the subtype rows under them by the cascade. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq("NODE_ID"))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .execute();
        dsl.deleteFrom(n)
            .where(n.GRAPH_NAME.eq(graph))
            .and(n.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
