package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ASSERT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ASSERT_TYPE_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ASSERT_TYPE_NODE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ASSERT_TYPE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_assert_type} and {@code graphitron_assert_type_node}: each {@code @assertType}
 * written on an argument or input field, and the condition tree beneath it, decoded from the
 * transcription's value tree by the rule views beside them, marked and swept; then the defects of what
 * a requirement names.
 *
 * <p>Runs first among the stages that resolve the graph, right after the federation vocabulary has
 * completed {@code graphitron_type}: the defects judge an atom against every type the composed schema
 * holds, and a generated scalar's kind and class are the macro expansion's, earlier still.
 */
public final class AssertTypes {

    private AssertTypes() {}

    /** States the graph's requirements and their trees, sweeps what the reading no longer finds, and raises their defects. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var a = GRAPHITRON_ASSERT_TYPE;
        var ar = GRAPHITRON_ASSERT_TYPE_RULE;
        dsl.insertInto(a)
            .columns(a.GRAPH_NAME, a.COORDINATE, a.ORDINAL, a.COMPONENT, a.TOUCHED_AT)
            .select(dsl
                .select(ar.GRAPH_NAME, ar.COORDINATE, ar.ORDINAL, ar.COMPONENT, val(touchedAt, a.TOUCHED_AT))
                .from(ar)
                .where(ar.GRAPH_NAME.eq(graph)))
            .onConflict(a.GRAPH_NAME, a.COORDINATE, a.ORDINAL)
            .doUpdate()
            .set(a.COMPONENT, excluded(a.COMPONENT))
            .set(a.TOUCHED_AT, excluded(a.TOUCHED_AT))
            .execute();
        var n = GRAPHITRON_ASSERT_TYPE_NODE;
        var nr = GRAPHITRON_ASSERT_TYPE_NODE_RULE;
        dsl.insertInto(n)
            .columns(n.GRAPH_NAME, n.COORDINATE, n.ORDINAL, n.NODE_PATH, n.PARENT_PATH, n.OPERATOR,
                n.TYPE_NAME, n.SOURCE_NAME, n.SOURCE_LINE, n.SOURCE_COLUMN, n.TOUCHED_AT)
            .select(dsl
                .select(nr.GRAPH_NAME, nr.COORDINATE, nr.ORDINAL, nr.NODE_PATH, nr.PARENT_PATH, nr.OPERATOR,
                    nr.TYPE_NAME, nr.SOURCE_NAME, nr.SOURCE_LINE, nr.SOURCE_COLUMN, val(touchedAt, n.TOUCHED_AT))
                .from(nr)
                .where(nr.GRAPH_NAME.eq(graph)))
            .onConflict(n.GRAPH_NAME, n.COORDINATE, n.ORDINAL, n.NODE_PATH)
            .doUpdate()
            .set(n.PARENT_PATH, excluded(n.PARENT_PATH))
            .set(n.OPERATOR, excluded(n.OPERATOR))
            .set(n.TYPE_NAME, excluded(n.TYPE_NAME))
            .set(n.SOURCE_NAME, excluded(n.SOURCE_NAME))
            .set(n.SOURCE_LINE, excluded(n.SOURCE_LINE))
            .set(n.SOURCE_COLUMN, excluded(n.SOURCE_COLUMN))
            .set(n.TOUCHED_AT, excluded(n.TOUCHED_AT))
            .execute();
        // The nodes first, so a requirement the reading no longer finds takes nothing a later
        // delete would have to reach; its nodes go with it by cascade either way.
        dsl.deleteFrom(n)
            .where(n.GRAPH_NAME.eq(graph))
            .and(n.TOUCHED_AT.ne(touchedAt))
            .execute();
        dsl.deleteFrom(a)
            .where(a.GRAPH_NAME.eq(graph))
            .and(a.TOUCHED_AT.ne(touchedAt))
            .execute();
        EntryDefects.derive(dsl, graph, touchedAt, EntryDefects.ASSERT_TYPES);
    }
}
