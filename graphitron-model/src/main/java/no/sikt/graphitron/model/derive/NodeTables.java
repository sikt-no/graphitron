package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_TABLE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_node_table}: each table nodes declare themselves over, how many do and
 * the one where one alone does, from the rule view beside it, marked and swept. Every rule asking
 * for the one node over a table reads it rather than counting the nodes again.
 *
 * <p>A step of the {@code nodes} stage, after {@link Nodes}, whose nodes it counts.
 */
public final class NodeTables {

    private NodeTables() {}

    /** States the nodes over every table at least one node of {@code graph} is bound to. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_NODE_TABLE;
        var r = GRAPHITRON_NODE_TABLE_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.NODE_COUNT,
                t.NODE_TYPE_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, r.NODE_COUNT,
                    r.NODE_TYPE_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME)
            .doUpdate()
            .set(t.NODE_COUNT, excluded(t.NODE_COUNT))
            .set(t.NODE_TYPE_NAME, excluded(t.NODE_TYPE_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** Deletes the tables this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_NODE_TABLE;
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
