package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.CODE_CONSTRUCTION;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.CODE_WRITE_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The build emitter's query: for every input object an argument fill hands a service call, how the
 * parameter's class is made from it. The class is the parameter's own, the element of a list; it is
 * made by its canonical constructor or by its setters, as {@code code_construction} says; and each
 * input field fills the member whose name is the field's bound name, a nested input object building
 * the member's class in turn. A nested input object that matches no member is a grouping, its fields
 * binding against the enclosing class as if they were declared there.
 *
 * <p>A node id field fills a member that is a jOOQ record by decoding into it: the record's own key
 * where it is a row of the node type's table, else its foreign key referencing that key. Several node
 * id fields may fill one member, decoding into the one record.
 *
 * <p>What cannot be made this way is refused with the reason, an input object being made into a
 * class nothing says how to construct, an input field matching no member, a list of groups, two
 * plain fields on one member, a node id that cannot be decoded into its member, and a parameter that
 * is a jOOQ record itself, which binds on columns rather than members and is not built here yet.
 */
public final class BuildQuery {

    private BuildQuery() {}

    /** One member a construction fills, at its call and argument, of the type the slot takes. */
    public record Slot(String methodName, String descriptor, int position, String slotName,
                       String slotType, String erasedClass, String elementClass) {}

    /**
     * What fills a member: the input field read along {@code path} from the build's own input map,
     * a grouping's names first; the kind of type it is; whether it is a list; and, for an input
     * object, the construction of the member's class.
     */
    public record Member(Slot slot, List<String> path, String typeKind, boolean list, Optional<Build> nested,
                         Optional<NodeIdDecodeQuery.Decode> decode) {
        public Member {
            path = List.copyOf(path);
            Objects.requireNonNull(nested, "nested");
            Objects.requireNonNull(decode, "decode");
        }
    }

    /** How one class is made: its construction shape and constructor, and the members filled. */
    public record Build(String className, String shape, String descriptor, List<Slot> slots,
                       List<Member> members) {
        public Build {
            slots = List.copyOf(slots);
            members = List.copyOf(members);
        }
    }

    /**
     * One column of a record built on the column axis, and the input fields that may write it, as
     * their paths from the input map in precedence order: the first one the client sent writes it.
     */
    public record ColumnWrite(String columnJavaName, List<List<String>> paths) {
        public ColumnWrite {
            paths = paths.stream().map(List::copyOf).toList();
        }
    }

    /**
     * How an input object is made into a table's jOOQ record: the record and its table as the generated
     * code names them, each column and the fields writing it, and the node id fields decoded into it.
     */
    public record RecordBuild(String recordClass, String tablesClass, String tableField,
                              List<ColumnWrite> columns, List<Member> decodes) {
        public RecordBuild {
            columns = List.copyOf(columns);
            decodes = List.copyOf(decodes);
        }
    }

    /** A root's answer: the construction, or why the input cannot be made into the class. */
    public sealed interface Result {
        record Built(Build build) implements Result {}
        record Refused(String reason) implements Result {}

        /**
         * A node id argument decoded straight into its parameter: into the parameter's own jOOQ
         * record ({@code RECORD}), or into the node type's record and taken from it as the key's
         * {@code RecordN} ({@code RECORD_N}) or {@code RowN} ({@code ROW_N}).
         */
        record Decoded(NodeIdDecodeQuery.Decode decode, String shape) implements Result {}

        /** An input object made into a table's jOOQ record on the column axis. */
        record Columns(RecordBuild build) implements Result {}
    }

    /** One call's argument fill to build: the call, the position and the occurrence it takes. */
    public record Root(String callCoordinate, int position, String landingCoordinate) {}

    private record InputField(String coordinate, String name, String boundName, String typeKind,
                              boolean list, boolean nodeId, int ordinal, boolean deprecated) {}

    private record Construction(String className, String shape, String descriptor, List<Slot> slots) {}

    /** Every input object a service call's argument fill takes, and how its parameter is made. */
    public static Map<Root, Result> read(StoreHandle store) {
        var children = inputFields(store);
        var constructions = constructions(store);
        var catalog = Catalog.read(store);
        var out = new HashMap<Root, Result>();
        roots(store).forEach((root, className) -> out.put(root,
            build(root.landingCoordinate(), className, children, constructions, catalog, new ArrayList<>())));
        nodeIdRoots(store).forEach((root, className) -> out.put(root,
            decodeParameter(root.landingCoordinate(), className, catalog)));
        return out;
    }

    /** The argument fills that land on a node id, with the class the parameter or its elements take. */
    private static Map<Root, String> nodeIdRoots(StoreHandle store) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var s = GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
        var p = CODE_METHOD_PARAMETER;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var out = new HashMap<Root, String>();
        store.dsl().select(c.COORDINATE, c.POSITION, c.LANDING_COORDINATE, coalesce(p.ELEMENT_CLASS, p.ERASED_CLASS))
            .from(c)
            .join(s).on(s.GRAPH_NAME.eq(c.GRAPH_NAME), s.COORDINATE.eq(c.COORDINATE))
            .join(p).on(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR), p.POSITION.eq(c.POSITION))
            .where(c.GRAPH_NAME.eq(store.graphName()), c.FILL.eq("ARGUMENT"),
                exists(selectOne().from(n).where(n.GRAPH_NAME.eq(c.GRAPH_NAME), n.COORDINATE.eq(c.LANDING_COORDINATE))))
            .forEach(r -> out.put(new Root(r.value1(), r.value2(), r.value3()), r.value4()));
        return out;
    }

    /**
     * How a node id argument fills a parameter of {@code className}: a table's record decodes it
     * directly, a {@code RecordN} or {@code RowN} takes the key off the node type's own record.
     */
    private static Result decodeParameter(String coordinate, String className, Catalog catalog) {
        NodeIdDecodeQuery.Resolution resolution;
        String shape;
        if (catalog.tables().containsKey(className)) {
            resolution = catalog.decodes().resolve(coordinate, className);
            shape = "RECORD";
        } else if (className.matches("org\\.jooq\\.Record\\d+")) {
            resolution = catalog.decodes().resolveOwn(coordinate);
            shape = "RECORD_N";
        } else if (className.matches("org\\.jooq\\.Row\\d+")) {
            resolution = catalog.decodes().resolveOwn(coordinate);
            shape = "ROW_N";
        } else {
            return new Result.Refused("a node id cannot be decoded into " + className);
        }
        return switch (resolution) {
            case NodeIdDecodeQuery.Resolution.Resolved resolved -> new Result.Decoded(resolved.decode(), shape);
            case NodeIdDecodeQuery.Resolution.Refused refused -> new Result.Refused(refused.reason());
        };
    }

    /** The argument fills that land on an input object, with the class the parameter takes. */
    private static Map<Root, String> roots(StoreHandle store) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var s = GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
        var p = CODE_METHOD_PARAMETER;
        var lo = GRAPHITRON_OCCURRENCE;
        var la = GRAPHITRON_ARGUMENT;
        var lf = GRAPHITRON_FIELD;
        var t = GRAPHITRON_TYPE;
        var out = new HashMap<Root, String>();
        store.dsl().select(c.COORDINATE, c.POSITION, c.LANDING_COORDINATE,
                coalesce(p.ELEMENT_CLASS, p.ERASED_CLASS))
            .from(c)
            .join(s).on(s.GRAPH_NAME.eq(c.GRAPH_NAME), s.COORDINATE.eq(c.COORDINATE))
            .join(p).on(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR), p.POSITION.eq(c.POSITION))
            .join(lo).on(lo.GRAPH_NAME.eq(c.GRAPH_NAME), lo.COORDINATE.eq(c.LANDING_COORDINATE))
            .leftJoin(la).on(la.GRAPH_NAME.eq(lo.GRAPH_NAME), la.COORDINATE.eq(lo.ELEMENT_COORDINATE))
            .leftJoin(lf).on(lf.GRAPH_NAME.eq(lo.GRAPH_NAME), lf.COORDINATE.eq(lo.ELEMENT_COORDINATE))
            .join(t).on(t.GRAPH_NAME.eq(lo.GRAPH_NAME), t.TYPE_NAME.eq(coalesce(la.NAMED_TYPE, lf.NAMED_TYPE)))
            .where(c.GRAPH_NAME.eq(store.graphName()), c.FILL.eq("ARGUMENT"), t.KIND.eq("INPUT_OBJECT"))
            .forEach(r -> out.put(new Root(r.value1(), r.value2(), r.value3()), r.value4()));
        return out;
    }

    /** Every input field occurrence, by the occurrence it is a field of. */
    private static Map<String, List<InputField>> inputFields(StoreHandle store) {
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var t = GRAPHITRON_TYPE;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var out = new HashMap<String, List<InputField>>();
        var d = GRAPHITRON_DEPRECATED;
        store.dsl().select(o.PARENT_COORDINATE, o.COORDINATE, o.NAME, f.BOUND_NAME, t.KIND, f.IS_LIST,
                field(exists(selectOne().from(n).where(n.GRAPH_NAME.eq(o.GRAPH_NAME), n.COORDINATE.eq(o.COORDINATE)))),
                f.ORDINAL,
                field(exists(selectOne().from(d).where(d.GRAPH_NAME.eq(f.GRAPH_NAME), d.COORDINATE.eq(f.COORDINATE)))))
            .from(o)
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(t).on(t.GRAPH_NAME.eq(f.GRAPH_NAME), t.TYPE_NAME.eq(f.NAMED_TYPE))
            .where(o.GRAPH_NAME.eq(store.graphName()), o.KIND.eq("INPUT_FIELD"))
            .orderBy(o.COORDINATE)
            .forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(
                new InputField(r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(),
                    r.value8(), r.value9())));
        return out;
    }

    /** Every class the code family says how to make, with the members it fills. */
    private static Map<String, Construction> constructions(StoreHandle store) {
        var k = CODE_CONSTRUCTION;
        var w = CODE_WRITE_SLOT;
        var out = new HashMap<String, Construction>();
        store.dsl().select(k.CLASS_NAME, k.SHAPE, k.DESCRIPTOR,
                multiset(select(w.METHOD_NAME, w.DESCRIPTOR, w.POSITION, w.SLOT_NAME, w.SLOT_TYPE,
                        w.ERASED_CLASS, w.ELEMENT_CLASS)
                    .from(w)
                    .where(w.SOURCE_NAME.eq(k.SOURCE_NAME), w.CLASS_NAME.eq(k.CLASS_NAME))
                    .orderBy(w.METHOD_NAME, w.POSITION))
                    .convertFrom(r -> r.map(Records.mapping(Slot::new))))
            .from(k)
            .forEach(r -> out.putIfAbsent(r.value1(), new Construction(r.value1(), r.value2(), r.value3(), r.value4())));
        return out;
    }


    private static Result build(String occurrence, String className, Map<String, List<InputField>> children,
                                Map<String, Construction> constructions, Catalog catalog,
                                List<String> prefix) {
        if (catalog.tables().containsKey(className)) {
            return columns(occurrence, className, children, catalog);
        }
        var construction = constructions.get(className);
        if (construction == null) {
            return new Result.Refused("nothing says how to make " + className);
        }
        var members = new ArrayList<Member>();
        var reason = bind(occurrence, construction, children, constructions, catalog, prefix, members);
        if (reason != null) {
            return new Result.Refused(reason);
        }
        var bound = new HashMap<String, String>();
        for (var m : members) {
            boolean sharedDecode = m.decode().isPresent() && members.stream()
                .filter(o -> o.slot().slotName().equals(m.slot().slotName()))
                .allMatch(o -> o.decode().isPresent());
            var earlier = bound.put(m.slot().slotName(), String.join(".", m.path()));
            if (earlier != null && !sharedDecode) {
                return new Result.Refused(earlier + " and " + String.join(".", m.path())
                    + " both fill member " + m.slot().slotName() + " of " + className);
            }
        }
        return new Result.Built(new Build(className, construction.shape(), construction.descriptor(),
            construction.slots(), members));
    }

    /**
     * An input object made into a table's jOOQ record: each plain field writes the column its bound
     * name names, a node id field decodes into the record's own key or its foreign key to the node's
     * table, and where several fields name one column a live one is preferred over a deprecated one,
     * the latest declared among deprecated ones, two live ones being refused for nothing saying which
     * the author meant.
     */
    private static Result columns(String occurrence, String recordClass, Map<String, List<InputField>> children,
                                  Catalog catalog) {
        var table = catalog.tables().get(recordClass);
        var tableKey = Catalog.key(table.source(), table.schema(), table.name());
        var byColumn = new java.util.LinkedHashMap<String, List<InputField>>();
        var decodes = new ArrayList<Member>();
        for (var field : children.getOrDefault(occurrence, List.of())) {
            if (field.nodeId()) {
                if (field.list()) {
                    return new Result.Refused("input field " + field.name() + " is a list of node ids, which is not built here yet");
                }
                var resolution = catalog.decodes().resolve(field.coordinate(), recordClass);
                if (resolution instanceof NodeIdDecodeQuery.Resolution.Refused refused) {
                    return new Result.Refused("input field " + field.name() + ": " + refused.reason());
                }
                var slot = new Slot(null, null, 0, field.boundName(), recordClass, recordClass, null);
                decodes.add(new Member(slot, List.of(field.name()), field.typeKind(), false, Optional.empty(),
                    Optional.of(((NodeIdDecodeQuery.Resolution.Resolved) resolution).decode())));
                continue;
            }
            if ("INPUT_OBJECT".equals(field.typeKind())) {
                return new Result.Refused("input field " + field.name() + " is an input object inside a jOOQ record, "
                    + "which is not built here yet");
            }
            var column = catalog.columns().get(tableKey + "." + field.boundName());
            if (column == null) {
                return new Result.Refused("input field " + field.name() + " names column " + field.boundName()
                    + ", which " + table.name() + " does not have");
            }
            byColumn.computeIfAbsent(column.javaName(), k -> new ArrayList<>()).add(field);
        }
        var writes = new ArrayList<ColumnWrite>();
        for (var entry : byColumn.entrySet()) {
            var fields = entry.getValue();
            var live = fields.stream().filter(f -> !f.deprecated()).toList();
            if (live.size() > 1) {
                return new Result.Refused("input fields " + live.stream().map(InputField::name).toList()
                    + " both write column " + entry.getKey() + " and neither is deprecated");
            }
            var ordered = new ArrayList<InputField>(live);
            fields.stream().filter(InputField::deprecated)
                .sorted(java.util.Comparator.comparingInt(InputField::ordinal).reversed())
                .forEach(ordered::add);
            writes.add(new ColumnWrite(entry.getKey(), ordered.stream().map(f -> List.of(f.name())).toList()));
        }
        return new Result.Columns(new RecordBuild(recordClass, table.tablesClass(), table.tableField(), writes, decodes));
    }

    /**
     * Binds the fields under {@code occurrence} to {@code construction}'s members, a grouping's
     * fields under its name; the reason the first one cannot bind, or null.
     */
    private static String bind(String occurrence, Construction construction,
                               Map<String, List<InputField>> children, Map<String, Construction> constructions,
                               Catalog catalog, List<String> prefix, List<Member> members) {
        for (var field : children.getOrDefault(occurrence, List.of())) {
            var path = new ArrayList<>(prefix);
            path.add(field.name());
            var slot = construction.slots().stream()
                .filter(s -> s.slotName().equals(field.boundName())).findFirst();
            if (slot.isEmpty()) {
                boolean renamed = !field.boundName().equals(field.name());
                if ("INPUT_OBJECT".equals(field.typeKind()) && !renamed) {
                    if (field.list()) {
                        return "input field " + String.join(".", path) + " matches no member of "
                            + construction.className() + " and is a list, so it cannot group";
                    }
                    var reason = bind(field.coordinate(), construction, children, constructions, catalog,
                        path, members);
                    if (reason != null) {
                        return reason;
                    }
                    continue;
                }
                return "input field " + String.join(".", path) + " names member " + field.boundName()
                    + ", which " + construction.className() + " does not have";
            }
            var memberClass = slot.get().elementClass() != null ? slot.get().elementClass() : slot.get().erasedClass();
            Optional<Build> nested = Optional.empty();
            Optional<NodeIdDecodeQuery.Decode> decode = Optional.empty();
            if (field.nodeId()) {
                if (field.list()) {
                    return "input field " + String.join(".", path) + " is a list of node ids, which is not built here yet";
                }
                var resolution = catalog.decodes().resolve(field.coordinate(), memberClass);
                if (resolution instanceof NodeIdDecodeQuery.Resolution.Refused refused) {
                    return "input field " + String.join(".", path) + ": " + refused.reason();
                }
                decode = Optional.of(((NodeIdDecodeQuery.Resolution.Resolved) resolution).decode());
            } else if ("INPUT_OBJECT".equals(field.typeKind())) {
                var result = build(field.coordinate(), memberClass, children, constructions, catalog, List.of());
                if (result instanceof Result.Refused refused) {
                    return refused.reason();
                }
                if (!(result instanceof Result.Built built)) {
                    return "input field " + String.join(".", path) + " fills a jOOQ record member, "
                        + "which is not built here yet";
                }
                nested = Optional.of(built.build());
            }
            members.add(new Member(slot.get(), path, field.typeKind(), field.list(), nested, decode));
        }
        return null;
    }

    /** A table as the generated code names it: its constants class and field, and its key. */
    private record Table(String source, String schema, String name, String tablesClass, String tableField) {}

    /** One column's generated name. */
    private record Column(String javaName) {}

    /**
     * The catalog facts a jOOQ record built on the column axis reads, the table each record class is
     * a row of and each column's generated name, and the node id decodes its fields resolve through.
     */
    private record Catalog(Map<String, Table> tables, Map<String, Column> columns, NodeIdDecodeQuery decodes) {

        static Catalog read(StoreHandle store) {
            var t = SQL_TABLE;
            var sc = SQL_SCHEMA;
            var tables = new HashMap<String, Table>();
            store.dsl().select(t.RECORD_CLASS_FQN, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME,
                    sc.TABLES_CLASS_FQN, t.JOOQ_NAME)
                .from(t)
                .join(sc).on(sc.SOURCE_NAME.eq(t.SOURCE_NAME), sc.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
                .forEach(r -> tables.putIfAbsent(r.value1(),
                    new Table(r.value2(), r.value3(), r.value4(), r.value5(), r.value6())));

            var c = SQL_COLUMN;
            var columns = new HashMap<String, Column>();
            store.dsl().select(c.SOURCE_NAME, c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME, c.JOOQ_NAME)
                .from(c)
                .forEach(r -> columns.put(key(r.value1(), r.value2(), r.value3()) + "." + r.value4(),
                    new Column(r.value5())));
            return new Catalog(tables, columns, NodeIdDecodeQuery.read(store));
        }

        static String key(String source, String schema, String table) {
            return source + "/" + schema + "." + table;
        }
    }
}
