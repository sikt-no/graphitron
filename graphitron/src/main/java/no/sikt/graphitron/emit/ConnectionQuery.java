package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONNECTION_PART;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_EMPTY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * The connection each producer of a database-per-tenant graph runs on, as the store states it: its outcome, its tenant part's answer to a call naming no tenant, the values the tenant is read
 * from and how each is read, and the producer's empty answer; one statement, the slots nested on their
 * call.
 */
public final class ConnectionQuery {

    private ConnectionQuery() {}

    /** Which connection a producer runs on, as {@code graphitron_occurrence_connection} spells it. */
    public enum Outcome { ROUTED, INHERIT, UNSCOPED }

    /** What a routed call naming no tenant gets, as its tenant part states it. */
    public enum WhenEmpty { UNREACHABLE, EMPTY, REFUSE }

    /** The collection a producer answers empty, as {@code graphitron_occurrence_producer_empty} spells it. */
    public enum EmptyKind { LIST, SET, RESULT }

    /**
     * The node a slot's value is an id of: its type id, the tables class and field of its table, and
     * the generated field names of its key columns in key order.
     */
    public record Node(String typeId, String tablesClass, String tableField, List<String> keyColumns) {
        public Node {
            Objects.requireNonNull(typeId, "typeId");
            keyColumns = List.copyOf(keyColumns);
        }
    }

    /**
     * One value the tenant is read from: the names from its argument down, and where it is a node id,
     * the node and the tenant column's position in its key.
     */
    public record Slot(List<String> names, Optional<Integer> keyPosition, Optional<Node> node) {
        public Slot {
            names = List.copyOf(names);
            Objects.requireNonNull(keyPosition, "keyPosition");
            Objects.requireNonNull(node, "node");
        }
    }

    /** The empty answer a producer returns without a connection: its kind, and a result's table. */
    public record Empty(EmptyKind kind, Optional<String> tablesClass, Optional<String> tableField) {}

    /** One call's connection. */
    public record Row(String coordinate, Outcome outcome, Optional<WhenEmpty> whenEmpty, List<Slot> slots,
                      Optional<Empty> empty) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(outcome, "outcome");
            slots = List.copyOf(slots);
        }
    }

    private record RawSlot(List<String> names, Integer keyPosition, String typeId, String tablesClass,
                           String tableField, List<String> keyColumns) {}

    /** The graph's producers' connections, by the producer's occurrence. */
    public static Map<String, Row> read(StoreHandle store) {
        var c = GRAPHITRON_OCCURRENCE_CONNECTION;
        var p = GRAPHITRON_OCCURRENCE_CONNECTION_PART;
        var s = GRAPHITRON_OCCURRENCE_TENANT_SLOT;
        var st = GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var nd = GRAPHITRON_NODE;
        var k = GRAPHITRON_NODE_KEYCOLUMN;
        var col = SQL_COLUMN;
        var t = SQL_TABLE;
        var sc = SQL_SCHEMA;
        var e = GRAPHITRON_OCCURRENCE_PRODUCER_EMPTY;
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        var rt = SQL_TABLE.as("rt");
        var rs = SQL_SCHEMA.as("rs");
        // A slot past a node id reads the node whose key holds the tenant column at its position.
        var nodeType = field(select(min(n.NODE_TYPE_NAME)).from(n)
            .where(n.GRAPH_NAME.eq(s.GRAPH_NAME), n.COORDINATE.eq(s.COORDINATE), n.POSITION.eq(s.KEY_POSITION)));
        var names = multiset(select(st.NAME).from(st)
                .where(st.GRAPH_NAME.eq(s.GRAPH_NAME), st.COORDINATE.eq(s.COORDINATE))
                .orderBy(st.DEPTH))
            .convertFrom(rs2 -> rs2.map(rec -> rec.value1()));
        var keyColumns = multiset(select(col.JOOQ_NAME).from(k)
                .join(col).on(col.SOURCE_NAME.eq(k.TABLE_SOURCE_NAME), col.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                    col.TABLE_NAME.eq(k.TABLE_NAME), col.COLUMN_NAME.eq(k.COLUMN_NAME))
                .where(k.GRAPH_NAME.eq(s.GRAPH_NAME), k.TYPE_NAME.eq(nodeType))
                .orderBy(k.POSITION))
            .convertFrom(rs2 -> rs2.map(rec -> rec.value1()));
        // The node's wire type id, resolved by the store and never null.
        var typeId = field(select(nd.TYPE_ID).from(nd)
            .where(nd.GRAPH_NAME.eq(s.GRAPH_NAME), nd.TYPE_NAME.eq(nodeType)));
        var tablesClass = field(select(min(sc.TABLES_CLASS_FQN)).from(k)
            .join(t).on(t.SOURCE_NAME.eq(k.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                t.TABLE_NAME.eq(k.TABLE_NAME))
            .join(sc).on(sc.SOURCE_NAME.eq(t.SOURCE_NAME), sc.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(k.GRAPH_NAME.eq(s.GRAPH_NAME), k.TYPE_NAME.eq(nodeType)));
        var tableField = field(select(min(t.JOOQ_NAME)).from(k)
            .join(t).on(t.SOURCE_NAME.eq(k.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                t.TABLE_NAME.eq(k.TABLE_NAME))
            .where(k.GRAPH_NAME.eq(s.GRAPH_NAME), k.TYPE_NAME.eq(nodeType)));
        var slots = multiset(select(names, s.KEY_POSITION, typeId, tablesClass, tableField, keyColumns)
                .from(s)
                .where(s.GRAPH_NAME.eq(c.GRAPH_NAME), s.CALL_COORDINATE.eq(c.COORDINATE), s.CALL.eq("producer"))
                .orderBy(s.SLOT))
            .convertFrom(rs2 -> rs2.map(Records.mapping(RawSlot::new)));
        return store.dsl()
            .select(c.COORDINATE, c.OUTCOME, p.WHEN_EMPTY, slots, e.EMPTY, rs.TABLES_CLASS_FQN, rt.JOOQ_NAME)
            .from(c)
            .leftJoin(p).on(p.GRAPH_NAME.eq(c.GRAPH_NAME), p.COORDINATE.eq(c.COORDINATE), p.PART.eq("TENANT"))
            .leftJoin(e).on(e.GRAPH_NAME.eq(c.GRAPH_NAME), e.COORDINATE.eq(c.COORDINATE))
            .leftJoin(r).on(r.GRAPH_NAME.eq(e.GRAPH_NAME), r.COORDINATE.eq(e.COORDINATE))
            .leftJoin(rt).on(rt.SOURCE_NAME.eq(r.TABLE_SOURCE_NAME), rt.TABLE_SCHEMA.eq(r.TABLE_SCHEMA),
                rt.TABLE_NAME.eq(r.TABLE_NAME))
            .leftJoin(rs).on(rs.SOURCE_NAME.eq(rt.SOURCE_NAME), rs.TABLE_SCHEMA.eq(rt.TABLE_SCHEMA))
            .where(c.GRAPH_NAME.eq(store.graphName()))
            .orderBy(c.COORDINATE)
            .fetch(rec -> new Row(rec.value1(), Outcome.valueOf(rec.value2()),
                Optional.ofNullable(rec.value3()).map(WhenEmpty::valueOf),
                rec.value4().stream().map(raw -> new Slot(raw.names(), Optional.ofNullable(raw.keyPosition()),
                    raw.typeId() == null || raw.keyPosition() == null ? Optional.empty()
                        : Optional.of(new Node(raw.typeId(), raw.tablesClass(), raw.tableField(), raw.keyColumns()))))
                    .toList(),
                Optional.ofNullable(rec.value5()).map(kind -> new Empty(EmptyKind.valueOf(kind),
                    Optional.ofNullable(rec.value6()), Optional.ofNullable(rec.value7())))))
            .stream()
            .collect(Collectors.toMap(Row::coordinate, row -> row, (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
