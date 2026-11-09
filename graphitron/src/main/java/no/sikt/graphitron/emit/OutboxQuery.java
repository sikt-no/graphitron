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

import static no.sikt.graphitron.model.Tables.CODE_READ_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_KEY_ROW_ACCESSOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_WRITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_MESSAGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ROUTINE_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_WRITE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.when;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * {@link OutboxEmitter}'s query: every {@code @outbox}-carrying coordinate the store admits, a
 * {@code @mutation} write or a mutation field a {@code @service} answers, with every catalog name
 * and member its companion method spells.
 *
 * <p>One driving statement at the grain of a coordinate, its messages and their leaves nested as
 * correlated {@code MULTISET}s on their own keys, a read of the node key of each node type a leaf
 * encodes, and a second statement, one row per graph, for the configured target. Nothing here decides
 * anything: membership is the write relation's row beside an {@code @outbox} message, a leaf is
 * a key the derivation kept, and the target is the row the derivation resolved. What failed to
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
    public enum LeafKind { ARGUMENT, RESULT_ROOT, RESULT_NODE_ID, RESULT_COLUMN, RESULT_MEMBER, SCOPE, SESSION }

    /** Whether an entry is the directive's own or the configured common payload's. */
    public enum Origin { COMMON, MESSAGE }

    /** What the payload column takes: the JSON value, or the JSON rendered as text. */
    public enum PayloadKind { JSON, TEXT }

    /**
     * One key of one message's payload. {@code path} is the source without its sigil (null for the
     * outcome as a whole); {@code column} is the written table's column a {@code RESULT_COLUMN}
     * reads, and {@code nodeType} the node type whose id a result leaf encodes, each null for every
     * other kind. {@code accessors} are the methods a key read off a service's returned value calls,
     * outermost first: to the member a {@code RESULT_MEMBER} ends on, or to the record a
     * {@code RESULT_NODE_ID} encodes; empty for every other key, and where the value passes through.
     */
    public record Leaf(String key, Origin origin, LeafKind kind, String path, Column column,
                       String nodeType, List<Accessor> accessors) {
        public Leaf {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(kind, "kind");
            accessors = List.copyOf(accessors);
        }

        /** The written path split on the dot, outermost first; empty where there is none. */
        public List<String> segments() {
            return path == null ? List.of() : List.of(path.split("\\."));
        }
    }

    /**
     * One method a key read off a service's returned value calls, and what it returns as the store
     * spells a member's yield; {@code slotType} is null for the member a field takes a record's keys
     * through, whose record is read as a jOOQ record.
     */
    public record Accessor(String method, String slotType) {
        public Accessor {
            Objects.requireNonNull(method, "method");
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
     * per parameter: the event's type and its payload, at the positions their parameters hold.
     * {@code payloadBindingType} is the Java type the payload parameter takes, which decides the
     * JSON value the call is handed.
     */
    public record RoutineTarget(String routinesClassName, String methodName, int parameterCount,
                                int typePosition, int payloadPosition,
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
     * Where the messages go: the outbox table and the columns its two roles land in, the event's
     * type and its payload. Its other columns are the database's to fill.
     */
    public record Target(Table table, Column typeColumn, Column payloadColumn,
                         PayloadKind payloadKind) implements Destination {
        public Target {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(typeColumn, "typeColumn");
            Objects.requireNonNull(payloadColumn, "payloadColumn");
            Objects.requireNonNull(payloadKind, "payloadKind");
        }
    }

    /**
     * What the messages describe: a write of a table, whose rows the messages read back by the key
     * it captured, or a {@code @service} call, whose returned value they read.
     */
    public sealed interface Producer permits Write, Service {}

    /** A {@code @mutation} write: its verb decides what the outcome can still be read from. */
    public record Write(WriteKind kind, Table table) implements Producer {
        public Write {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(table, "table");
        }
    }

    /** A {@code @service} on a mutation field: it writes no one table, and hands back a value. */
    public record Service() implements Producer {}

    /** A node type's table and key columns, in key order: what its id is encoded from. */
    public record NodeKey(Table table, List<Column> columns) {
        public NodeKey {
            Objects.requireNonNull(table, "table");
            columns = List.copyOf(columns);
        }
    }

    /** Where an {@code @outbox} is written, for a verdict on it. */
    public record Location(String sourceName, int line, int column) {}

    /**
     * One {@code @outbox}-carrying coordinate.
     *
     * @param nodeKeys the node types whose id the result leaves encode, keyed by name; empty where
     *                 no leaf encodes one
     * @param at       where the field's first {@code @outbox} is written
     */
    public record Row(String typeName, String fieldName, Producer producer, Location at,
                      Destination target, List<Message> messages,
                      java.util.Map<String, NodeKey> nodeKeys) {
        public Row {
            Objects.requireNonNull(producer, "producer");
            Objects.requireNonNull(at, "at");
            messages = List.copyOf(messages);
            nodeKeys = java.util.Map.copyOf(nodeKeys);
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

    /** Reads every {@code @outbox}-carrying coordinate the store admits, writes and services. */
    public static Rows read(StoreHandle store) {
        var target = target(store).or(() -> routineTarget(store));
        if (target.isEmpty()) {
            // No target row is a configuration the build refused before the generator ran; a run
            // that reaches here with outbox applications and none has no store to read them from.
            return Rows.empty();
        }
        var rows = new java.util.ArrayList<Row>(coordinates(store, target.get()));
        rows.addAll(services(store, target.get()));
        rows.sort(java.util.Comparator.comparing(Row::typeName).thenComparing(Row::fieldName));
        return new Rows(rows);
    }

    private record RawLeaf(String key, String origin, String kind, String path, String columnSql,
                           String columnJava, String columnType, String nodeType) {}

    private record RawMessage(int ordinal, String event, List<RawLeaf> leaves) {}

    /** The field's first {@code @outbox}, where a verdict on its messages is reported. */
    private static Location location(StoreHandle store, String coordinate) {
        var o = GRAPHITRON_OUTBOX_MESSAGE;
        return store.dsl()
            .select(o.SOURCE_NAME, o.SOURCE_LINE, o.SOURCE_COLUMN)
            .from(o)
            .where(o.GRAPH_NAME.eq(store.graphName()), o.COORDINATE.eq(coordinate))
            .orderBy(o.ORDINAL)
            .limit(1)
            .fetchOne(r -> new Location(r.value1(), r.value2(), r.value3()));
    }

    /**
     * The mutation fields a {@code @service} answers that carry an {@code @outbox}: what the store
     * admitted, since a placement it refused stopped the build before the generator ran.
     */
    private static List<Row> services(StoreHandle store, Destination target) {
        var f = GRAPHITRON_FIELD;
        return store.dsl()
            .select(f.TYPE_NAME, f.FIELD_NAME, f.COORDINATE, serviceMessages(f))
            .from(f)
            .where(f.GRAPH_NAME.eq(store.graphName()), exists(serviceMessage(f)))
            .orderBy(f.TYPE_NAME, f.FIELD_NAME)
            .fetch(r -> {
                List<RawMessage> raw = r.value4();
                var messages = raw.stream().map(m -> message(store, r.value3(), m, true)).toList();
                return new Row(r.value1(), r.value2(), new Service(), location(store, r.value3()), target,
                    messages, nodeKeys(store, raw));
            });
    }

    /** An {@code @outbox} of {@code f} whose occurrence a {@code @service} answers and no write is. */
    private static org.jooq.Select<?> serviceMessage(no.sikt.graphitron.model.tables.GraphitronField f) {
        var o = GRAPHITRON_OUTBOX_MESSAGE.as("service_message");
        var op = GRAPHITRON_OCCURRENCE_PRODUCER;
        var w = GRAPHITRON_OCCURRENCE_PRODUCER_WRITE;
        return selectOne()
            .from(o)
            .join(op).on(op.GRAPH_NAME.eq(o.GRAPH_NAME), op.COORDINATE.eq(o.OCCURRENCE_COORDINATE_REF),
                op.KIND.eq("SERVICE"))
            .where(o.GRAPH_NAME.eq(f.GRAPH_NAME), o.COORDINATE.eq(f.COORDINATE),
                DSL.notExists(selectOne().from(w).where(w.GRAPH_NAME.eq(o.GRAPH_NAME),
                    w.COORDINATE.eq(o.OCCURRENCE_COORDINATE_REF))));
    }

    /** A service field's messages in written order, each with its keys; no written table to join. */
    private static Field<List<RawMessage>> serviceMessages(no.sikt.graphitron.model.tables.GraphitronField f) {
        var o = GRAPHITRON_OUTBOX_MESSAGE.as("message");
        var l = GRAPHITRON_OUTBOX_KEY;
        Field<String> kind = when(l.FILL.eq("RESULT"), concat(inline("RESULT_"), l.READS))
            .when(l.FILL.eq("SESSION_MEMBER"), inline("SESSION"))
            .otherwise(l.FILL);
        Field<String> none = DSL.castNull(String.class);
        return multiset(
            select(o.ORDINAL, o.EVENT, multiset(
                    select(l.KEY_NAME, l.ORIGIN, kind, l.SOURCE_PATH, none, none, none, l.NODE_TYPE_NAME)
                        .from(l)
                        .where(l.GRAPH_NAME.eq(o.GRAPH_NAME), l.COORDINATE.eq(o.COORDINATE),
                            l.ORDINAL.eq(o.ORDINAL))
                        .orderBy(l.ORIGIN, l.POSITION))
                    .convertFrom(rs -> rs.map(Records.mapping(RawLeaf::new))))
                .from(o)
                .where(o.GRAPH_NAME.eq(f.GRAPH_NAME), o.COORDINATE.eq(f.COORDINATE))
                .orderBy(o.ORDINAL))
            .convertFrom(rs -> rs.map(Records.mapping(RawMessage::new)));
    }

    /**
     * The methods a key read off a service's returned value calls, outermost first: each step of its
     * path that reads a member, by the member its value binding names, or for the record a node id is
     * encoded from, by the member its field takes its keys through. A step the value passes through
     * calls nothing, and a node id's own last step is the encoding, not a call.
     */
    private static List<Accessor> accessors(StoreHandle store, String coordinate, int ordinal, String key,
                                            boolean nodeId) {
        var st = GRAPHITRON_ARGMAPPING_STEP;
        var oc = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var v = GRAPHITRON_OCCURRENCE_BINDING_VALUE;
        var ka = GRAPHITRON_FIELD_KEY_ROW_ACCESSOR;
        var cs = CODE_READ_SLOT;
        var method = DSL.coalesce(v.SLOT_METHOD_NAME, ka.SLOT_METHOD_NAME);
        return store.dsl()
            .selectDistinct(st.CONSUMED, method, cs.SLOT_TYPE)
            .from(st)
            .join(oc).on(oc.GRAPH_NAME.eq(st.GRAPH_NAME), oc.COORDINATE.eq(st.STEP_COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(oc.GRAPH_NAME), f.COORDINATE.eq(oc.ELEMENT_COORDINATE))
            .leftJoin(v).on(v.GRAPH_NAME.eq(st.GRAPH_NAME), v.COORDINATE.eq(st.STEP_COORDINATE))
            .leftJoin(ka).on(ka.GRAPH_NAME.eq(f.GRAPH_NAME), ka.TYPE_NAME.eq(f.TYPE_NAME),
                ka.FIELD_NAME.eq(f.FIELD_NAME))
            .leftJoin(cs).on(cs.SOURCE_NAME.eq(v.SLOT_SOURCE_NAME), cs.CLASS_NAME.eq(v.SLOT_CLASS_NAME),
                cs.METHOD_NAME.eq(v.SLOT_METHOD_NAME), cs.DESCRIPTOR.eq(v.SLOT_DESCRIPTOR))
            .where(st.GRAPH_NAME.eq(store.graphName()), st.DIRECTIVE_NAME.eq("outbox"),
                st.COORDINATE.eq(coordinate), st.ORDINAL.eq(ordinal), st.PARAMETER_NAME.eq(key),
                nodeId ? st.CONSUMED.lt(st.SEGMENTS) : DSL.noCondition())
            .orderBy(st.CONSUMED)
            .fetch(r -> r.value2() == null ? null : new Accessor(r.value2(), r.value3()))
            .stream().filter(Objects::nonNull).toList();
    }

    private static List<Row> coordinates(StoreHandle store, Destination target) {
        var w = GRAPHITRON_PRODUCER_WRITE;
        var o = GRAPHITRON_OUTBOX_MESSAGE.as("outbox_message");
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
                    o.COORDINATE.eq(concat(w.TYPE_NAME, inline("."), w.FIELD_NAME)))))
            .orderBy(w.TYPE_NAME, w.FIELD_NAME)
            .fetch(r -> {
                var table = new Table(r.value4(), r.value5(), r.value6(), r.value7());
                List<RawMessage> raw = r.value8();
                String coordinate = r.value1() + "." + r.value2();
                var messages = raw.stream().map(m -> message(store, coordinate, m, false)).toList();
                return new Row(r.value1(), r.value2(), new Write(WriteKind.valueOf(r.value3()), table),
                    location(store, coordinate), target, messages, nodeKeys(store, raw));
            });
    }

    private static Message message(StoreHandle store, String coordinate, RawMessage m, boolean service) {
        return new Message(m.ordinal(), m.event(), m.leaves().stream()
            .map(l -> {
                var kind = LeafKind.valueOf(l.kind());
                boolean readsValue = service && (kind == LeafKind.RESULT_MEMBER || kind == LeafKind.RESULT_NODE_ID);
                return new Leaf(l.key(), Origin.valueOf(l.origin()), kind, l.path(),
                    l.columnSql() == null ? null
                        : new Column(l.columnSql(), l.columnJava(), l.columnType()),
                    l.nodeType(),
                    readsValue
                        ? accessors(store, coordinate, m.ordinal(), l.key(), kind == LeafKind.RESULT_NODE_ID)
                        : List.of());
            })
            .toList());
    }

    /** The table and key columns of every node type a result leaf encodes, the columns in key order. */
    private static java.util.Map<String, NodeKey> nodeKeys(StoreHandle store, List<RawMessage> messages) {
        var types = messages.stream()
            .flatMap(m -> m.leaves().stream())
            .map(RawLeaf::nodeType)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        var out = new java.util.LinkedHashMap<String, NodeKey>();
        var k = GRAPHITRON_NODE_KEYCOLUMN;
        var c = SQL_COLUMN;
        var t = SQL_TABLE.as("node_table");
        var s = SQL_SCHEMA.as("node_schema");
        for (var type : types) {
            var rows = store.dsl()
                .select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE,
                    t.TABLE_NAME, t.JOOQ_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN)
                .from(k)
                .join(c).on(c.SOURCE_NAME.eq(k.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                    c.TABLE_NAME.eq(k.TABLE_NAME), c.COLUMN_NAME.eq(k.COLUMN_NAME))
                .join(t).on(t.SOURCE_NAME.eq(k.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(k.TABLE_SCHEMA),
                    t.TABLE_NAME.eq(k.TABLE_NAME))
                .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
                .where(k.GRAPH_NAME.eq(store.graphName()), k.TYPE_NAME.eq(type))
                .orderBy(k.POSITION)
                .fetch();
            if (rows.isEmpty()) {
                continue;
            }
            var first = rows.getFirst();
            out.put(type, new NodeKey(
                new Table(first.value4(), first.value5(), first.value6(), first.value7()),
                rows.map(r -> new Column(r.value1(), r.value2(), r.value3()))));
        }
        return out;
    }

    /** The field's messages in written order, each with its leaves. */
    private static Field<List<RawMessage>> messages(
            no.sikt.graphitron.model.tables.GraphitronProducerWrite w) {
        // Each message at the field, with the event it is published under.
        var o = GRAPHITRON_OUTBOX_MESSAGE.as("message");
        return multiset(
            select(o.ORDINAL, o.EVENT, leaves(o, w))
                .from(o)
                .where(o.GRAPH_NAME.eq(w.GRAPH_NAME),
                    o.COORDINATE.eq(concat(w.TYPE_NAME, inline("."), w.FIELD_NAME)))
                .orderBy(o.ORDINAL))
            .convertFrom(r -> r.map(Records.mapping(RawMessage::new)));
    }

    /**
     * A message's leaves, the common payload's first and each in its written order. A
     * {@code RESULT_COLUMN} reads a column of the written table, so its catalog name comes off a
     * join to it.
     */
    private static Field<List<RawLeaf>> leaves(
            no.sikt.graphitron.model.tables.GraphitronOutboxMessage o,
            no.sikt.graphitron.model.tables.GraphitronProducerWrite w) {
        var l = GRAPHITRON_OUTBOX_KEY;
        var rc = SQL_COLUMN.as("result_column");
        // What fills the key, in the emitter's terms: a result key by what it reads of the result.
        Field<String> kind = when(l.FILL.eq("RESULT"), concat(inline("RESULT_"), l.READS))
            .when(l.FILL.eq("SESSION_MEMBER"), inline("SESSION"))
            .otherwise(l.FILL);
        return multiset(
            select(l.KEY_NAME, l.ORIGIN, kind, l.SOURCE_PATH, rc.COLUMN_NAME, rc.JOOQ_NAME,
                    rc.BINDING_TYPE, l.NODE_TYPE_NAME)
                .from(l)
                .leftJoin(rc).on(rc.SOURCE_NAME.eq(w.TABLE_SOURCE_NAME),
                    rc.TABLE_SCHEMA.eq(w.TABLE_SCHEMA), rc.TABLE_NAME.eq(w.TABLE_NAME),
                    rc.COLUMN_NAME.eq(l.COLUMN_NAME))
                .where(l.GRAPH_NAME.eq(o.GRAPH_NAME), l.COORDINATE.eq(o.COORDINATE),
                    l.ORDINAL.eq(o.ORDINAL))
                .orderBy(l.ORIGIN, l.POSITION))
            .convertFrom(r -> r.map(Records.mapping(RawLeaf::new)));
    }

    /** The graph's resolved outbox target, or empty where the configuration resolved to none. */
    private static Optional<Destination> target(StoreHandle store) {
        var t = GRAPHITRON_OUTBOX_TARGET;
        var tt = SQL_TABLE.as("target_table");
        var ts = SQL_SCHEMA.as("target_schema");
        var tc = SQL_COLUMN.as("type_col");
        var pc = SQL_COLUMN.as("payload_col");
        return store.dsl()
            .select(tt.TABLE_NAME, tt.JOOQ_NAME, tt.CLASS_FQN, ts.TABLES_CLASS_FQN,
                tc.COLUMN_NAME, tc.JOOQ_NAME, tc.BINDING_TYPE,
                pc.COLUMN_NAME, pc.JOOQ_NAME, pc.BINDING_TYPE, t.PAYLOAD_KIND)
            .from(t)
            .join(tt).on(tt.SOURCE_NAME.eq(t.TABLE_SOURCE_NAME), tt.TABLE_SCHEMA.eq(t.TABLE_SCHEMA),
                tt.TABLE_NAME.eq(t.TABLE_NAME))
            .join(ts).on(ts.SOURCE_NAME.eq(tt.SOURCE_NAME), ts.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA))
            .join(tc).on(tc.SOURCE_NAME.eq(tt.SOURCE_NAME), tc.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                tc.TABLE_NAME.eq(tt.TABLE_NAME), tc.COLUMN_NAME.eq(t.TYPE_COLUMN))
            .join(pc).on(pc.SOURCE_NAME.eq(tt.SOURCE_NAME), pc.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                pc.TABLE_NAME.eq(tt.TABLE_NAME), pc.COLUMN_NAME.eq(t.PAYLOAD_COLUMN))
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .fetchOptional(r -> (Destination) new Target(
                new Table(r.value1(), r.value2(), r.value3(), r.value4()),
                new Column(r.value5(), r.value6(), r.value7()),
                new Column(r.value8(), r.value9(), r.value10()),
                PayloadKind.valueOf(r.value11())));
    }

    /** The graph's resolved outbox routine, or empty where the configuration resolved to none. */
    private static Optional<Destination> routineTarget(StoreHandle store) {
        var t = GRAPHITRON_OUTBOX_ROUTINE_TARGET;
        var r = SQL_ROUTINE;
        var p = SQL_ROUTINE_PARAMETER.as("payload_parameter");
        return store.dsl()
            .select(r.ROUTINES_CLASS_FQN, r.ROUTINES_METHOD_NAME, t.TYPE_POSITION,
                t.PAYLOAD_POSITION, p.BINDING_TYPE, t.PAYLOAD_KIND,
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
                row.value7(), row.value3(), row.value4(), row.value5(),
                PayloadKind.valueOf(row.value6())));
    }
}
