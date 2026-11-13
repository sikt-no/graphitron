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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BUILT_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN_RECORD_ACCESSOR;
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
     * their paths from the input map in precedence order: the first one the client sent writes it;
     * with the Bean Validation property the record reads the column through, where the catalog's
     * record has a getter for it.
     */
    public record ColumnWrite(String columnJavaName, List<List<String>> paths, Optional<String> property) {
        public ColumnWrite {
            paths = paths.stream().map(List::copyOf).toList();
            Objects.requireNonNull(property, "property");
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
         * {@code RecordN} ({@code RECORD_N}), {@code RowN} ({@code ROW_N}) or the value of the one
         * key column a pair projects ({@code KEY_VALUE}), whose generated name {@code column} is.
         */
        record Decoded(NodeIdDecodeQuery.Decode decode, String shape, Optional<String> column)
            implements Result {}

        /** An input object made into a table's jOOQ record on the column axis. */
        record Columns(RecordBuild build) implements Result {}
    }

    /** One call's argument fill to build: the call, the position and the occurrence it takes. */
    public record Root(String callCoordinate, int position, String landingCoordinate) {}

    private record InputField(String coordinate, String name, String boundName, String typeKind,
                              boolean list, boolean nodeId, int ordinal, boolean deprecated) {}

    private record Construction(String className, String shape, String descriptor, List<Slot> slots) {}

    /**
     * What the store says an input object is built into and how each field fills it: the class each
     * occurrence is built into, by occurrence and class, with how it got it and the classpath entry
     * the class is read from, and the member each field fills, by field and the class it is of.
     */
    private record Builds(Map<String, Built> classes, Map<String, String> members) {

        record Built(String declaredVia, String sourceRef, boolean record) {}

        static String key(String coordinate, String className) {
            return coordinate + "|" + className;
        }

        static Builds read(StoreHandle store) {
            var c = GRAPHITRON_OCCURRENCE_BUILT_CLASS;
            var classes = new HashMap<String, Built>();
            store.dsl().select(c.COORDINATE, c.CLASS_NAME, c.DECLARED_VIA, c.CLASS_SOURCE_REF, c.RECORD_TABLE)
                .from(c)
                .where(c.GRAPH_NAME.eq(store.graphName()))
                .forEach(r -> classes.put(key(r.value1(), r.value2()),
                    new Built(r.value3(), r.value4(), r.value5() != null)));
            var m = GRAPHITRON_OCCURRENCE_BINDING_MEMBER;
            var members = new HashMap<String, String>();
            store.dsl().select(m.COORDINATE, m.CLASS_NAME, m.SLOT_METHOD_NAME, m.SLOT_DESCRIPTOR, m.SLOT_POSITION)
                .from(m)
                .where(m.GRAPH_NAME.eq(store.graphName()))
                .forEach(r -> members.put(key(r.value1(), r.value2()), slotKey(r.value3(), r.value4(), r.value5())));
            return new Builds(classes, members);
        }

        static String slotKey(String methodName, String descriptor, int position) {
            return methodName + descriptor + ":" + position;
        }
    }

    /** Every input object a service call's argument fill takes, and how its parameter is made. */
    public static Map<Root, Result> read(StoreHandle store) {
        var children = inputFields(store);
        var constructions = constructions(store);
        var catalog = Catalog.read(store);
        var builds = Builds.read(store);
        var out = new HashMap<Root, Result>();
        roots(store).forEach((root, rooted) -> out.put(root, rooted.condition() && catalog.tables().containsKey(rooted.className())
            ? new Result.Refused("a @condition parameter takes no jOOQ record: take the map or a bean")
            : build(root.landingCoordinate(), rooted.className(), children, constructions, catalog, builds,
                new ArrayList<>())));
        nodeIdRoots(store).forEach((root, shapes) -> out.put(root,
            decodeParameter(root.landingCoordinate(), shapes, catalog)));
        return out;
    }

    /** A node id parameter's decode shape at one node table, with the key column a pair projects. */
    private record SlotShape(String shape, Optional<String> projectedColumn) {}

    /**
     * Each @service call's parameter a node id lands on, with its decode shape per node table as
     * graphitron_occurrence_node_id_shape states it at the parameter's slot, and the generated name
     * of the one key column a pair projects into it, where one does. A parameter no shape fits has
     * none, and is the slot defects' to refuse.
     */
    private static Map<Root, List<SlotShape>> nodeIdRoots(StoreHandle store) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var sp = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
        var d = GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE;
        var pj = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION;
        var kp = GRAPHITRON_OCCURRENCE_NODE_ID;
        var col = SQL_COLUMN;
        var out = new HashMap<Root, List<SlotShape>>();
        store.dsl().select(c.COORDINATE, c.POSITION, c.LANDING_COORDINATE, d.DECODE_SHAPE, col.JOOQ_NAME)
            .from(sp)
            // The service call's parameter, so another kind's fills stay out.
            .join(c).on(c.GRAPH_NAME.eq(sp.GRAPH_NAME), c.COORDINATE.eq(sp.CALL_COORDINATE), c.CALL.eq(sp.CALL),
                c.POSITION.eq(sp.POSITION), c.CALL_KIND.eq("SERVICE"))
            .leftJoin(d).on(d.GRAPH_NAME.eq(sp.GRAPH_NAME), d.COORDINATE.eq(sp.COORDINATE), d.SLOT.eq(sp.SLOT))
            .leftJoin(pj).on(pj.GRAPH_NAME.eq(sp.GRAPH_NAME), pj.CALL_COORDINATE.eq(sp.CALL_COORDINATE),
                pj.CALL.eq(sp.CALL), pj.POSITION.eq(sp.POSITION), pj.BOUND.eq(d.BOUND))
            .leftJoin(kp).on(kp.GRAPH_NAME.eq(pj.GRAPH_NAME), kp.COORDINATE.eq(pj.COORDINATE),
                kp.BOUND.eq(pj.BOUND), kp.POSITION.eq(pj.KEY_POSITION))
            .leftJoin(col).on(col.SOURCE_NAME.eq(kp.TABLE_SOURCE_NAME), col.TABLE_SCHEMA.eq(kp.TABLE_SCHEMA),
                col.TABLE_NAME.eq(kp.TABLE_NAME), col.COLUMN_NAME.eq(kp.COLUMN_NAME))
            .where(sp.GRAPH_NAME.eq(store.graphName()))
            .forEach(r -> {
                var shapes = out.computeIfAbsent(new Root(r.value1(), r.value2(), r.value3()),
                    k -> new ArrayList<>());
                if (r.value4() != null) {
                    shapes.add(new SlotShape(r.value4(), Optional.ofNullable(r.value5())));
                }
            });
        return out;
    }

    /**
     * How a node id argument fills its parameter, in the decode shape
     * {@code graphitron_occurrence_node_id_shape} states at the parameter's slot: the node type's own
     * record decodes it directly, and a {@code RecordN}, a {@code RowN} or the projected key column's
     * value is taken off that record. A parameter no shape fits is the slot defects' to refuse, and
     * one with a shape per member table is an id of several node types.
     */
    private static Result decodeParameter(String coordinate, List<SlotShape> shapes, Catalog catalog) {
        if (shapes.isEmpty()) {
            return new Result.Refused("a node id cannot be decoded into the parameter");
        }
        if (shapes.size() > 1) {
            return new Result.Refused("the id is one of several node types, which is not built here yet");
        }
        var decodeShape = shapes.getFirst().shape();
        NodeIdDecodeQuery.Resolution resolution;
        String shape;
        switch (decodeShape) {
            case "OWN_RECORD" -> {
                resolution = catalog.decodes().resolveOwn(coordinate);
                shape = "RECORD";
            }
            case "RECORD_N", "ROW_N", "KEY_VALUE" -> {
                resolution = catalog.decodes().resolveOwn(coordinate);
                shape = decodeShape;
            }
            default -> {
                return new Result.Refused("the id is one of several node types, which is not built here yet");
            }
        }
        return switch (resolution) {
            case NodeIdDecodeQuery.Resolution.Resolved resolved ->
                new Result.Decoded(resolved.decode(), shape, shapes.getFirst().projectedColumn());
            case NodeIdDecodeQuery.Resolution.Refused refused -> new Result.Refused(refused.reason());
        };
    }

    /** The class an input object's parameter takes, and whether a {@code @condition}'s call takes it. */
    private record Rooted(String className, boolean condition) {}

    /**
     * The argument fills that land on an input object, a service call's or a condition's, with the class
     * the parameter takes.
     */
    private static Map<Root, Rooted> roots(StoreHandle store) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var s = GRAPHITRON_OCCURRENCE_CALL_METHOD;
        var p = CODE_METHOD_PARAMETER;
        var lo = GRAPHITRON_OCCURRENCE;
        var la = GRAPHITRON_ARGUMENT;
        var lf = GRAPHITRON_FIELD;
        var t = GRAPHITRON_TYPE;
        var out = new HashMap<Root, Rooted>();
        store.dsl().select(c.COORDINATE, c.POSITION, c.LANDING_COORDINATE,
                coalesce(p.ELEMENT_CLASS, p.ERASED_CLASS), s.CALL_KIND)
            .from(c)
            .join(s).on(s.GRAPH_NAME.eq(c.GRAPH_NAME), s.COORDINATE.eq(c.COORDINATE), s.CALL.eq(c.CALL),
                s.CALL_KIND.in("SERVICE", "CONDITION"))
            .join(p).on(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR), p.POSITION.eq(c.POSITION))
            .join(lo).on(lo.GRAPH_NAME.eq(c.GRAPH_NAME), lo.COORDINATE.eq(c.LANDING_COORDINATE))
            .leftJoin(la).on(la.GRAPH_NAME.eq(lo.GRAPH_NAME), la.COORDINATE.eq(lo.ELEMENT_COORDINATE))
            .leftJoin(lf).on(lf.GRAPH_NAME.eq(lo.GRAPH_NAME), lf.COORDINATE.eq(lo.ELEMENT_COORDINATE))
            .join(t).on(t.GRAPH_NAME.eq(lo.GRAPH_NAME), t.TYPE_NAME.eq(coalesce(la.NAMED_TYPE, lf.NAMED_TYPE)))
            .where(c.GRAPH_NAME.eq(store.graphName()), c.FILL.eq("ARGUMENT"), t.KIND.eq("INPUT_OBJECT"))
            // A condition applied to several tables makes one call per table, taking the same bean.
            .forEach(r -> out.put(new Root(r.value1(), r.value2(), r.value3()),
                new Rooted(r.value4(), "CONDITION".equals(r.value5()))));
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
            // In the order the input type declares its fields, which is the order a record's columns
            // are written in.
            .orderBy(o.PARENT_COORDINATE, f.ORDINAL)
            .forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(
                new InputField(r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(),
                    r.value8(), r.value9())));
        return out;
    }

    /**
     * Every class the code family says how to make, with the members it fills, by the classpath entry
     * it is read from and its name.
     */
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
                    .convertFrom(r -> r.map(Records.mapping(Slot::new))),
                k.SOURCE_NAME)
            .from(k)
            .forEach(r -> out.put(r.value5() + "|" + r.value1(),
                new Construction(r.value1(), r.value2(), r.value3(), r.value4())));
        return out;
    }


    private static Result build(String occurrence, String className, Map<String, List<InputField>> children,
                                Map<String, Construction> constructions, Catalog catalog, Builds builds,
                                List<String> prefix) {
        // The class as graphitron_occurrence_built_class names it, read from the entry it names, and
        // a jOOQ record where the row says it is one.
        var built = builds.classes().get(Builds.key(occurrence, className));
        if (built != null && built.record()) {
            return columns(occurrence, className, children, catalog);
        }
        var construction = built == null ? null : constructions.get(built.sourceRef() + "|" + className);
        if (construction == null) {
            return new Result.Refused("nothing says how to make " + className);
        }
        var members = new ArrayList<Member>();
        var reason = bind(occurrence, construction, children, constructions, catalog, builds, prefix, members);
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
     * An input object made into a table's jOOQ record: each plain field writes the column the store
     * binds its occurrence to on the record's table, in the order the input type declares them; a
     * node id field decodes into the record's own key or its foreign key to the node's table; and
     * where several fields name one column a live one is preferred over a deprecated one, the latest
     * declared among deprecated ones, two live ones being refused for nothing saying which the author
     * meant. A nested input object is a grouping, its fields writing the record as if they were
     * declared where it is, read through it.
     */
    private static Result columns(String occurrence, String recordClass, Map<String, List<InputField>> children,
                                  Catalog catalog) {
        var table = catalog.tables().get(recordClass);
        var byColumn = new java.util.LinkedHashMap<String, List<Placed>>();
        var properties = new HashMap<String, Optional<String>>();
        var decodes = new ArrayList<Member>();
        var reason = place(occurrence, recordClass, table, List.of(), children, catalog, byColumn, properties, decodes);
        if (reason != null) {
            return new Result.Refused(reason);
        }
        var writes = new ArrayList<ColumnWrite>();
        for (var entry : byColumn.entrySet()) {
            var fields = entry.getValue();
            var live = fields.stream().filter(f -> !f.field().deprecated()).toList();
            if (live.size() > 1) {
                return new Result.Refused("input fields " + live.stream().map(f -> String.join(".", f.path())).toList()
                    + " both write column " + entry.getKey() + " and neither is deprecated");
            }
            var ordered = new ArrayList<Placed>(live);
            fields.stream().filter(f -> f.field().deprecated())
                .sorted(java.util.Comparator.comparingInt((Placed f) -> f.field().ordinal()).reversed())
                .forEach(ordered::add);
            writes.add(new ColumnWrite(entry.getKey(), ordered.stream().map(Placed::path).toList(),
                properties.get(entry.getKey())));
        }
        return new Result.Columns(new RecordBuild(recordClass, table.tablesClass(), table.tableField(), writes, decodes));
    }

    /** An input field writing a record, with the path it is read through. */
    private record Placed(InputField field, List<String> path) {}

    /**
     * Places the fields under {@code occurrence} on the record's columns and decodes, a grouping's
     * fields under its name; the reason the first one cannot be placed, or null.
     */
    private static String place(String occurrence, String recordClass, Table table, List<String> prefix,
                                Map<String, List<InputField>> children, Catalog catalog,
                                Map<String, List<Placed>> byColumn, Map<String, Optional<String>> properties,
                                List<Member> decodes) {
        var tableKey = Catalog.key(table.source(), table.schema(), table.name());
        for (var field : children.getOrDefault(occurrence, List.of())) {
            var path = new ArrayList<>(prefix);
            path.add(field.name());
            var named = String.join(".", path);
            if (field.nodeId()) {
                if (field.list()) {
                    return "input field " + named + " is a list of node ids, which is not built here yet";
                }
                var resolution = catalog.decodes().resolve(field.coordinate(), recordClass);
                if (resolution instanceof NodeIdDecodeQuery.Resolution.Refused refused) {
                    return "input field " + named + ": " + refused.reason();
                }
                var decode = ((NodeIdDecodeQuery.Resolution.Resolved) resolution).decode();
                var slot = new Slot(null, null, 0, field.boundName(), recordClass, recordClass, null);
                decodes.add(new Member(slot, path, field.typeKind(), false, Optional.empty(), Optional.of(decode)));
                continue;
            }
            if ("INPUT_OBJECT".equals(field.typeKind())) {
                if (field.list()) {
                    return "input field " + named + " is a list of input objects inside a jOOQ record, so it cannot group";
                }
                var reason = place(field.coordinate(), recordClass, table, path, children, catalog, byColumn,
                    properties, decodes);
                if (reason != null) {
                    return reason;
                }
                continue;
            }
            // The column the store binds the field's occurrence to on the record's table.
            var column = catalog.occurrenceColumns().get(field.coordinate() + "|" + tableKey);
            if (column == null) {
                return "input field " + named + " binds no column of " + table.name();
            }
            byColumn.computeIfAbsent(column.javaName(), k -> new ArrayList<>()).add(new Placed(field, path));
            properties.put(column.javaName(), column.property());
        }
        return null;
    }

    /**
     * Binds the fields under {@code occurrence} to {@code construction}'s members, a grouping's
     * fields under its name; the reason the first one cannot bind, or null. The member each field
     * fills is its {@code MEMBER} binding's, and a field filling none is a grouping where
     * {@code graphitron_occurrence_built_class} says it is built into the same class.
     */
    private static String bind(String occurrence, Construction construction,
                               Map<String, List<InputField>> children, Map<String, Construction> constructions,
                               Catalog catalog, Builds builds, List<String> prefix, List<Member> members) {
        var className = construction.className();
        for (var field : children.getOrDefault(occurrence, List.of())) {
            var path = new ArrayList<>(prefix);
            path.add(field.name());
            var filled = builds.members().get(Builds.key(field.coordinate(), className));
            var slot = filled == null ? Optional.<Slot>empty() : construction.slots().stream()
                .filter(s -> Builds.slotKey(s.methodName(), s.descriptor(), s.position()).equals(filled)).findFirst();
            if (slot.isEmpty()) {
                var built = builds.classes().get(Builds.key(field.coordinate(), className));
                if (built != null && "GROUPING".equals(built.declaredVia())) {
                    var reason = bind(field.coordinate(), construction, children, constructions, catalog, builds,
                        path, members);
                    if (reason != null) {
                        return reason;
                    }
                    continue;
                }
                if ("INPUT_OBJECT".equals(field.typeKind()) && field.list()) {
                    return "input field " + String.join(".", path) + " matches no member of "
                        + className + " and is a list, so it cannot group";
                }
                return "input field " + String.join(".", path) + " fills no member of " + className;
            }
            var memberClass = slot.get().elementClass() != null ? slot.get().elementClass() : slot.get().erasedClass();
            Optional<Build> nested = Optional.empty();
            Optional<NodeIdDecodeQuery.Decode> decode = Optional.empty();
            if (field.nodeId()) {
                // A list of node ids decodes each into a record of its own, the member's element.
                var resolution = catalog.decodes().resolve(field.coordinate(), memberClass);
                if (resolution instanceof NodeIdDecodeQuery.Resolution.Refused refused) {
                    return "input field " + String.join(".", path) + ": " + refused.reason();
                }
                decode = Optional.of(((NodeIdDecodeQuery.Resolution.Resolved) resolution).decode());
            } else if ("INPUT_OBJECT".equals(field.typeKind())) {
                var result = build(field.coordinate(), memberClass, children, constructions, catalog, builds,
                    List.of());
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
    private record Column(String javaName, Optional<String> property) {}

    /**
     * The catalog facts a jOOQ record built on the column axis reads, the table each record class is
     * a row of and each column's generated name, and the node id decodes its fields resolve through.
     */
    private record Catalog(Map<String, Table> tables, Map<String, Column> occurrenceColumns,
                           NodeIdDecodeQuery decodes) {

        static Catalog read(StoreHandle store) {
            var t = SQL_TABLE;
            var sc = SQL_SCHEMA;
            var tables = new HashMap<String, Table>();
            store.dsl().select(t.RECORD_CLASS_FQN, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME,
                    sc.TABLES_CLASS_FQN, t.JOOQ_NAME)
                .from(t)
                .join(sc).on(sc.SOURCE_NAME.eq(t.SOURCE_NAME), sc.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
                .where(store.reads(t.SOURCE_NAME))
                .forEach(r -> tables.putIfAbsent(r.value1(),
                    new Table(r.value2(), r.value3(), r.value4(), r.value5(), r.value6())));

            // Each occurrence's column on each table it lands on, by the occurrence and the table.
            // With the property the record's getter offers for it, where it has one.
            var c = SQL_COLUMN;
            var oc = GRAPHITRON_OCCURRENCE_COLUMN;
            var a = SQL_COLUMN_RECORD_ACCESSOR;
            var columns = new HashMap<String, Column>();
            store.dsl().select(oc.COORDINATE, oc.TABLE_SOURCE_NAME, oc.TABLE_SCHEMA, oc.TABLE_NAME, c.JOOQ_NAME,
                    a.PROPERTY_NAME)
                .from(oc)
                .join(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                    c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
                .leftJoin(a).on(a.SOURCE_NAME.eq(c.SOURCE_NAME), a.TABLE_SCHEMA.eq(c.TABLE_SCHEMA),
                    a.TABLE_NAME.eq(c.TABLE_NAME), a.COLUMN_NAME.eq(c.COLUMN_NAME))
                .where(oc.GRAPH_NAME.eq(store.graphName()))
                .forEach(r -> columns.put(r.value1() + "|" + key(r.value2(), r.value3(), r.value4()),
                    new Column(r.value5(), Optional.ofNullable(r.value6()))));

            return new Catalog(tables, columns, NodeIdDecodeQuery.read(store));
        }

        static String key(String source, String schema, String table) {
            return source + "/" + schema + "." + table;
        }
    }
}
