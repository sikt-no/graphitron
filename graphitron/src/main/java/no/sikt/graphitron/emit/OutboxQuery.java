package no.sikt.graphitron.emit;

import no.sikt.graphitron.emit.RoutineWriteQuery.Column;
import no.sikt.graphitron.emit.RoutineWriteQuery.Table;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_LEAF;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ROUTINE_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_WRITE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * {@link OutboxEmitter}'s query: every {@code @outbox}-carrying write coordinate the store admits,
 * with every catalog name its companion method spells.
 *
 * <p>One driving statement at the grain of a coordinate, its messages and their leaves nested as
 * correlated {@code MULTISET}s on their own keys, a read of the node key of each node type a leaf
 * encodes, and a second statement, one row per graph, for the configured target. Nothing here decides
 * anything: membership is the write relation's row beside an {@code @outbox} application, a leaf is
 * a row the derivation kept, and the target is the row the derivation resolved. What failed to
 * resolve left no row and is a build error before the generator runs, so every name read here is
 * captured rather than derived and a row is built with no live catalog.
 *
 * <p>Cost: the population is the graph's {@code @outbox} fields, small by construction, and the
 * nested reads are stored rows read on their key once per field.
 */
public final class OutboxQuery {

    private OutboxQuery() {}

    /** The write the messages describe: its verb decides what the outcome can still be read from. */
    public enum WriteKind { INSERT, UPDATE, DELETE }

    /** Where a payload key comes from. */
    public enum LeafKind { ARGUMENT, RESULT_ROOT, RESULT_NODE_ID, RESULT_COLUMN, SCOPE, SESSION }

    /** Whether an entry is the directive's own or the configured common payload's. */
    public enum Origin { COMMON, MESSAGE }

    /** What the payload column takes: the JSON value, or the JSON rendered as text. */
    public enum PayloadKind { JSON, TEXT }

    /**
     * One key of one message's payload. {@code path} is the source without its sigil (null for the
     * outcome as a whole); {@code column} is the written table's column a {@code RESULT_COLUMN}
     * reads, and {@code nodeType} the node type whose id a result leaf encodes, each null for every
     * other kind.
     */
    public record Leaf(String key, Origin origin, LeafKind kind, String path, Column column,
                       String nodeType) {
        public Leaf {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(kind, "kind");
        }

        /** The written path split on the dot, outermost first; empty where there is none. */
        public List<String> segments() {
            return path == null ? List.of() : List.of(path.split("\\."));
        }
    }

    /**
     * One message: its place among the field's applications, the event it carries, and its keys,
     * the common payload's first.
     */
    public record Message(int ordinal, String event, List<Leaf> leaves) {
        public Message {
            Objects.requireNonNull(event, "event");
            leaves = List.copyOf(leaves);
        }
    }

    /** Where the messages go: an outbox table, or an outbox routine called once per message. */
    public sealed interface Destination permits Target, RoutineTarget {}

    /**
     * An outbox routine: a procedure or a function that is no table, called through its
     * {@code Configuration}-first method on the generated {@code Routines} class, with one argument
     * per parameter. The positions say which parameter each role lands in; an optional role is empty
     * where the routine has no such parameter. {@code payloadBindingType} is the Java type the
     * payload parameter takes, which decides the JSON value the call is handed.
     */
    public record RoutineTarget(String routinesClassName, String methodName, int parameterCount,
                                int typePosition, java.util.OptionalInt aggregateTypePosition,
                                java.util.OptionalInt aggregateIdPosition, int payloadPosition,
                                String payloadBindingType, PayloadKind payloadKind)
            implements Destination {
        public RoutineTarget {
            Objects.requireNonNull(routinesClassName, "routinesClassName");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(payloadBindingType, "payloadBindingType");
            Objects.requireNonNull(payloadKind, "payloadKind");
        }
    }

    /**
     * Where the messages go: the outbox table and the column each role lands in. The optional
     * roles are empty where the table has no such column.
     */
    public record Target(Table table, Column typeColumn, Optional<Column> aggregateTypeColumn,
                         Optional<Column> aggregateIdColumn, Column payloadColumn,
                         PayloadKind payloadKind) implements Destination {
        public Target {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(typeColumn, "typeColumn");
            Objects.requireNonNull(payloadColumn, "payloadColumn");
            Objects.requireNonNull(payloadKind, "payloadKind");
        }
    }

    /**
     * One {@code @outbox}-carrying write coordinate.
     *
     * @param nodeColumns the columns of the node type whose id the result leaves encode, keyed by
     *                    that node type's name, in key order; empty where no leaf encodes one
     */
    public record Row(String typeName, String fieldName, WriteKind writeKind, Table writtenTable,
                      Destination target, List<Message> messages,
                      java.util.Map<String, List<Column>> nodeColumns) {
        public Row {
            messages = List.copyOf(messages);
            nodeColumns = java.util.Map.copyOf(nodeColumns);
            if (messages.isEmpty()) {
                throw new IllegalStateException(typeName + "." + fieldName + " carries no message");
            }
        }
    }

    /**
     * Every admitted coordinate, in coordinate order, keyed by the coordinate: a second row for one
     * coordinate fails here rather than emitting two methods of one name.
     */
    public record Rows(List<Row> rows) {

        public Rows {
            rows = List.copyOf(rows);
            var seen = new java.util.HashSet<String>();
            for (var r : rows) {
                if (!seen.add(r.typeName() + "." + r.fieldName())) {
                    throw new IllegalArgumentException(
                        "the outbox rows are keyed by coordinate; " + r.typeName() + "."
                        + r.fieldName() + " appears twice");
                }
            }
        }

        /** No rows: a graph with no {@code @outbox}, or a run with no store. */
        public static Rows empty() {
            return new Rows(List.of());
        }

        /** The row for one coordinate, empty when the coordinate carries no {@code @outbox}. */
        public Optional<Row> rowFor(String typeName, String fieldName) {
            return rows.stream()
                .filter(r -> r.typeName().equals(typeName) && r.fieldName().equals(fieldName))
                .findFirst();
        }
    }

    /** Reads every {@code @outbox}-carrying write coordinate the store admits. */
    public static Rows read(StoreHandle store) {
        var target = target(store).or(() -> routineTarget(store));
        if (target.isEmpty()) {
            // No target row is a configuration the build refused before the generator ran; a run
            // that reaches here with outbox applications and none has no store to read them from.
            return Rows.empty();
        }
        return new Rows(coordinates(store, target.get()));
    }

    private record RawLeaf(String key, String origin, String kind, String path, String columnSql,
                           String columnJava, String columnType, String nodeType) {}

    private record RawMessage(int ordinal, String event, List<RawLeaf> leaves) {}

    private static List<Row> coordinates(StoreHandle store, Destination target) {
        var w = GRAPHITRON_PRODUCER_WRITE;
        var o = GRAPHITRON_OUTBOX_ENTRY;
        var wt = SQL_TABLE.as("written_table");
        var ws = SQL_SCHEMA.as("written_schema");
        return store.dsl()
            .select(w.TYPE_NAME, w.FIELD_NAME, w.KIND, wt.TABLE_NAME, wt.JOOQ_NAME, wt.CLASS_FQN,
                ws.TABLES_CLASS_FQN, messages(w))
            .from(w)
            .join(wt).on(wt.SOURCE_NAME.eq(w.TABLE_SOURCE_NAME), wt.TABLE_SCHEMA.eq(w.TABLE_SCHEMA),
                wt.TABLE_NAME.eq(w.TABLE_NAME))
            .join(ws).on(ws.SOURCE_NAME.eq(wt.SOURCE_NAME), ws.TABLE_SCHEMA.eq(wt.TABLE_SCHEMA))
            .where(w.GRAPH_NAME.eq(store.graphName()),
                exists(selectOne().from(o).where(o.GRAPH_NAME.eq(w.GRAPH_NAME),
                    o.TYPE_NAME.eq(w.TYPE_NAME), o.FIELD_NAME.eq(w.FIELD_NAME))))
            .orderBy(w.TYPE_NAME, w.FIELD_NAME)
            .fetch(r -> {
                var table = new Table(r.value4(), r.value5(), r.value6(), r.value7());
                List<RawMessage> raw = r.value8();
                var messages = raw.stream().map(OutboxQuery::message).toList();
                return new Row(r.value1(), r.value2(), WriteKind.valueOf(r.value3()), table,
                    target, messages, nodeColumns(store, raw));
            });
    }

    private static Message message(RawMessage m) {
        return new Message(m.ordinal(), m.event(), m.leaves().stream()
            .map(l -> new Leaf(l.key(), Origin.valueOf(l.origin()), LeafKind.valueOf(l.kind()),
                l.path(),
                l.columnSql() == null ? null
                    : new Column(l.columnSql(), l.columnJava(), l.columnType()),
                l.nodeType()))
            .toList());
    }

    /** The key columns of every node type a result leaf encodes, in key order. */
    private static java.util.Map<String, List<Column>> nodeColumns(StoreHandle store,
                                                                   List<RawMessage> messages) {
        var types = messages.stream()
            .flatMap(m -> m.leaves().stream())
            .map(RawLeaf::nodeType)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        var out = new java.util.LinkedHashMap<String, List<Column>>();
        var k = GRAPHITRON_NODE_KEYCOLUMN;
        var c = SQL_COLUMN;
        for (var type : types) {
            out.put(type, store.dsl()
                .select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE)
                .from(k)
                .join(c).on(c.SOURCE_NAME.eq(k.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                    c.TABLE_NAME.eq(k.TABLE_NAME), c.COLUMN_NAME.eq(k.COLUMN_NAME))
                .where(k.GRAPH_NAME.eq(store.graphName()), k.TYPE_NAME.eq(type))
                .orderBy(k.POSITION)
                .fetch(r -> new Column(r.value1(), r.value2(), r.value3())));
        }
        return out;
    }

    /** The field's messages in written order, each with its leaves. */
    private static Field<List<RawMessage>> messages(
            no.sikt.graphitron.model.tables.GraphitronProducerWrite w) {
        var o = GRAPHITRON_OUTBOX_ENTRY.as("message");
        return multiset(
            select(o.ORDINAL, o.EVENT, leaves(o, w))
                .from(o)
                .where(o.GRAPH_NAME.eq(w.GRAPH_NAME), o.TYPE_NAME.eq(w.TYPE_NAME),
                    o.FIELD_NAME.eq(w.FIELD_NAME))
                .orderBy(o.ORDINAL))
            .convertFrom(r -> r.map(Records.mapping(RawMessage::new)));
    }

    /**
     * A message's leaves, the common payload's first and each in its written order. A
     * {@code RESULT_COLUMN} reads a column of the written table, so its catalog name comes off a
     * join to it.
     */
    private static Field<List<RawLeaf>> leaves(
            no.sikt.graphitron.model.tables.GraphitronOutboxEntry o,
            no.sikt.graphitron.model.tables.GraphitronProducerWrite w) {
        var l = GRAPHITRON_OUTBOX_LEAF;
        var rc = SQL_COLUMN.as("result_column");
        return multiset(
            select(l.KEY_NAME, l.ORIGIN, l.SOURCE_KIND, l.SOURCE_PATH, rc.COLUMN_NAME, rc.JOOQ_NAME,
                    rc.BINDING_TYPE, l.NODE_TYPE_NAME)
                .from(l)
                .leftJoin(rc).on(rc.SOURCE_NAME.eq(w.TABLE_SOURCE_NAME),
                    rc.TABLE_SCHEMA.eq(w.TABLE_SCHEMA), rc.TABLE_NAME.eq(w.TABLE_NAME),
                    rc.COLUMN_NAME.eq(l.COLUMN_NAME))
                .where(l.GRAPH_NAME.eq(o.GRAPH_NAME), l.TYPE_NAME.eq(o.TYPE_NAME),
                    l.FIELD_NAME.eq(o.FIELD_NAME), l.ORDINAL.eq(o.ORDINAL))
                .orderBy(l.ORIGIN, l.POSITION))
            .convertFrom(r -> r.map(Records.mapping(RawLeaf::new)));
    }

    /** The graph's resolved outbox target, or empty where the configuration resolved to none. */
    private static Optional<Destination> target(StoreHandle store) {
        var t = GRAPHITRON_OUTBOX_TARGET;
        var tt = SQL_TABLE.as("target_table");
        var ts = SQL_SCHEMA.as("target_schema");
        var tc = SQL_COLUMN.as("type_col");
        var ac = SQL_COLUMN.as("aggregatetype_col");
        var ic = SQL_COLUMN.as("aggregateid_col");
        var pc = SQL_COLUMN.as("payload_col");
        return store.dsl()
            .select(tt.TABLE_NAME, tt.JOOQ_NAME, tt.CLASS_FQN, ts.TABLES_CLASS_FQN,
                tc.COLUMN_NAME, tc.JOOQ_NAME, tc.BINDING_TYPE,
                ac.COLUMN_NAME, ac.JOOQ_NAME, ac.BINDING_TYPE,
                ic.COLUMN_NAME, ic.JOOQ_NAME, ic.BINDING_TYPE,
                pc.COLUMN_NAME, pc.JOOQ_NAME, pc.BINDING_TYPE, t.PAYLOAD_KIND)
            .from(t)
            .join(tt).on(tt.SOURCE_NAME.eq(t.TABLE_SOURCE_NAME), tt.TABLE_SCHEMA.eq(t.TABLE_SCHEMA),
                tt.TABLE_NAME.eq(t.TABLE_NAME))
            .join(ts).on(ts.SOURCE_NAME.eq(tt.SOURCE_NAME), ts.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA))
            .join(tc).on(tc.SOURCE_NAME.eq(tt.SOURCE_NAME), tc.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                tc.TABLE_NAME.eq(tt.TABLE_NAME), tc.COLUMN_NAME.eq(t.TYPE_COLUMN))
            .leftJoin(ac).on(ac.SOURCE_NAME.eq(tt.SOURCE_NAME), ac.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                ac.TABLE_NAME.eq(tt.TABLE_NAME), ac.COLUMN_NAME.eq(t.AGGREGATETYPE_COLUMN))
            .leftJoin(ic).on(ic.SOURCE_NAME.eq(tt.SOURCE_NAME), ic.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                ic.TABLE_NAME.eq(tt.TABLE_NAME), ic.COLUMN_NAME.eq(t.AGGREGATEID_COLUMN))
            .join(pc).on(pc.SOURCE_NAME.eq(tt.SOURCE_NAME), pc.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                pc.TABLE_NAME.eq(tt.TABLE_NAME), pc.COLUMN_NAME.eq(t.PAYLOAD_COLUMN))
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .fetchOptional(r -> (Destination) new Target(
                new Table(r.value1(), r.value2(), r.value3(), r.value4()),
                new Column(r.value5(), r.value6(), r.value7()),
                r.value8() == null ? Optional.empty()
                    : Optional.of(new Column(r.value8(), r.value9(), r.value10())),
                r.value11() == null ? Optional.empty()
                    : Optional.of(new Column(r.value11(), r.value12(), r.value13())),
                new Column(r.value14(), r.value15(), r.value16()),
                PayloadKind.valueOf(r.value17())));
    }

    /** The graph's resolved outbox routine, or empty where the configuration resolved to none. */
    private static Optional<Destination> routineTarget(StoreHandle store) {
        var t = GRAPHITRON_OUTBOX_ROUTINE_TARGET;
        var r = SQL_ROUTINE;
        var p = SQL_ROUTINE_PARAMETER.as("payload_parameter");
        return store.dsl()
            .select(r.ROUTINES_CLASS_FQN, r.ROUTINES_METHOD_NAME, t.TYPE_POSITION,
                t.AGGREGATETYPE_POSITION, t.AGGREGATEID_POSITION, t.PAYLOAD_POSITION, p.BINDING_TYPE,
                t.PAYLOAD_KIND,
                DSL.field(DSL.selectCount().from(SQL_ROUTINE_PARAMETER)
                    .where(SQL_ROUTINE_PARAMETER.SOURCE_NAME.eq(r.SOURCE_NAME),
                        SQL_ROUTINE_PARAMETER.TABLE_SCHEMA.eq(r.TABLE_SCHEMA),
                        SQL_ROUTINE_PARAMETER.ROUTINE_NAME.eq(r.ROUTINE_NAME))))
            .from(t)
            .join(r).on(r.SOURCE_NAME.eq(t.ROUTINE_SOURCE_NAME), r.TABLE_SCHEMA.eq(t.ROUTINE_SCHEMA),
                r.ROUTINE_NAME.eq(t.ROUTINE_NAME))
            .join(p).on(p.SOURCE_NAME.eq(r.SOURCE_NAME), p.TABLE_SCHEMA.eq(r.TABLE_SCHEMA),
                p.ROUTINE_NAME.eq(r.ROUTINE_NAME), p.POSITION.eq(t.PAYLOAD_POSITION))
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .fetchOptional(row -> (Destination) new RoutineTarget(row.value1(), row.value2(),
                row.value9(), row.value3(),
                row.value4() == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(row.value4()),
                row.value5() == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(row.value5()),
                row.value6(), row.value7(), PayloadKind.valueOf(row.value8())));
    }
}
