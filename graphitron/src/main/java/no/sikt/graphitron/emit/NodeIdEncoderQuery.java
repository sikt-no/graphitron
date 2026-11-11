package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * The node id encoder's query: one row per node type of the graph, each with what its
 * {@code encode<TypeName>} and {@code decode<TypeName>} helpers are made of. A node answers to its
 * wire type id and is keyed by its key columns, in key order, on the table it is bound to; that is
 * the whole of what the helpers bake in, so it is the whole of what this reads.
 *
 * <p>Every node in {@code graphitron_node} draws a row, a federation stub included: a stub's id binds,
 * encodes and decodes like any node's, only {@code Query.node} passing it by. A node whose table's
 * schema publishes no {@code Tables} class draws none, since no helper can name its columns. A node
 * whose key resolved to no column is a defect the store states ({@code NODE_WITHOUT_KEY},
 * {@code NODE_KEY_COLUMN_UNRESOLVED}), so no graph that generates has one.
 */
public final class NodeIdEncoderQuery {

    /**
     * One node type: its name, the wire type id its ids carry, the {@code Tables} class and field its
     * table is reached through, and its key columns in key order.
     */
    public record Node(String typeName, String typeId, String tablesClass, String tableField,
                       List<ColumnRef> keyColumns) {
        public Node {
            keyColumns = List.copyOf(keyColumns);
        }
    }

    private NodeIdEncoderQuery() {}

    /** The graph's nodes, in type name order. */
    public static List<Node> read(StoreHandle store) {
        var n = GRAPHITRON_NODE;
        var tt = GRAPHITRON_TABLETYPE;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var k = GRAPHITRON_NODE_KEYCOLUMN;
        var c = SQL_COLUMN;
        return store.dsl()
            .select(n.TYPE_NAME, n.TYPE_ID, s.TABLES_CLASS_FQN, t.JOOQ_NAME,
                multiset(select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
                    .from(k)
                    .join(c).on(c.SOURCE_NAME.eq(k.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                        c.TABLE_NAME.eq(k.TABLE_NAME), c.COLUMN_NAME.eq(k.COLUMN_NAME))
                    .where(k.GRAPH_NAME.eq(n.GRAPH_NAME), k.TYPE_NAME.eq(n.TYPE_NAME))
                    .orderBy(k.POSITION))
                    .convertFrom(r -> r.map(c4 -> new ColumnRef(c4.value1(), c4.value2(), c4.value3(), c4.value4()))))
            .from(n)
            .join(tt).on(tt.GRAPH_NAME.eq(n.GRAPH_NAME), tt.TYPE_NAME.eq(n.TYPE_NAME))
            .join(t).on(t.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                t.TABLE_NAME.eq(tt.TABLE_NAME))
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(n.GRAPH_NAME.eq(store.graphName()), s.TABLES_CLASS_FQN.isNotNull())
            .orderBy(n.TYPE_NAME)
            .fetch(r -> new Node(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
    }
}
