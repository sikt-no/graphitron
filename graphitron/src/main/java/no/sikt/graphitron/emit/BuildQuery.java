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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
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
 * <p>What cannot be made this way is refused with the reason, an input object being made into a
 * class nothing says how to construct, an input field matching no member, a list of groups, two
 * fields on one member, and the jOOQ record and node id axes, which bind on columns rather than
 * members and are not built here yet.
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
    public record Member(Slot slot, List<String> path, String typeKind, boolean list, Optional<Build> nested) {
        public Member {
            path = List.copyOf(path);
            Objects.requireNonNull(nested, "nested");
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

    /** A root's answer: the construction, or why the input cannot be made into the class. */
    public sealed interface Result {
        record Built(Build build) implements Result {}
        record Refused(String reason) implements Result {}
    }

    /** One call's argument fill to build: the call, the position and the occurrence it takes. */
    public record Root(String callCoordinate, int position, String landingCoordinate) {}

    private record InputField(String coordinate, String name, String boundName, String typeKind,
                              boolean list, boolean nodeId) {}

    private record Construction(String className, String shape, String descriptor, List<Slot> slots) {}

    /** Every input object a service call's argument fill takes, and how its parameter is made. */
    public static Map<Root, Result> read(StoreHandle store) {
        var children = inputFields(store);
        var constructions = constructions(store);
        var records = recordClasses(store);
        var out = new HashMap<Root, Result>();
        roots(store).forEach((root, className) -> out.put(root,
            build(root.landingCoordinate(), className, children, constructions, records, new ArrayList<>())));
        return out;
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
        store.dsl().select(o.PARENT_COORDINATE, o.COORDINATE, o.NAME, f.BOUND_NAME, t.KIND, f.IS_LIST,
                field(exists(selectOne().from(n).where(n.GRAPH_NAME.eq(o.GRAPH_NAME), n.COORDINATE.eq(o.COORDINATE)))))
            .from(o)
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(t).on(t.GRAPH_NAME.eq(f.GRAPH_NAME), t.TYPE_NAME.eq(f.NAMED_TYPE))
            .where(o.GRAPH_NAME.eq(store.graphName()), o.KIND.eq("INPUT_FIELD"))
            .orderBy(o.COORDINATE)
            .forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(
                new InputField(r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7())));
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

    /** The record classes of the catalog's tables, which bind on columns rather than members. */
    private static java.util.Set<String> recordClasses(StoreHandle store) {
        return new java.util.HashSet<>(store.dsl().select(SQL_TABLE.RECORD_CLASS_FQN).from(SQL_TABLE)
            .where(SQL_TABLE.RECORD_CLASS_FQN.isNotNull())
            .fetch(SQL_TABLE.RECORD_CLASS_FQN));
    }

    private static Result build(String occurrence, String className, Map<String, List<InputField>> children,
                                Map<String, Construction> constructions, java.util.Set<String> records,
                                List<String> prefix) {
        if (records.contains(className)) {
            return new Result.Refused(className + " is a jOOQ record, which binds on columns");
        }
        var construction = constructions.get(className);
        if (construction == null) {
            return new Result.Refused("nothing says how to make " + className);
        }
        var members = new ArrayList<Member>();
        var reason = bind(occurrence, construction, children, constructions, records, prefix, members);
        if (reason != null) {
            return new Result.Refused(reason);
        }
        var bound = new HashMap<String, String>();
        for (var m : members) {
            var earlier = bound.put(m.slot().slotName(), String.join(".", m.path()));
            if (earlier != null) {
                return new Result.Refused(earlier + " and " + String.join(".", m.path())
                    + " both fill member " + m.slot().slotName() + " of " + className);
            }
        }
        return new Result.Built(new Build(className, construction.shape(), construction.descriptor(),
            construction.slots(), members));
    }

    /**
     * Binds the fields under {@code occurrence} to {@code construction}'s members, a grouping's
     * fields under its name; the reason the first one cannot bind, or null.
     */
    private static String bind(String occurrence, Construction construction,
                               Map<String, List<InputField>> children, Map<String, Construction> constructions,
                               java.util.Set<String> records, List<String> prefix, List<Member> members) {
        for (var field : children.getOrDefault(occurrence, List.of())) {
            var path = new ArrayList<>(prefix);
            path.add(field.name());
            var slot = construction.slots().stream()
                .filter(s -> s.slotName().equals(field.boundName())).findFirst();
            if (field.nodeId()) {
                return "input field " + String.join(".", path) + " is a node id, which decodes into "
                    + "its key and is not built here yet";
            }
            if (slot.isEmpty()) {
                boolean renamed = !field.boundName().equals(field.name());
                if ("INPUT_OBJECT".equals(field.typeKind()) && !renamed) {
                    if (field.list()) {
                        return "input field " + String.join(".", path) + " matches no member of "
                            + construction.className() + " and is a list, so it cannot group";
                    }
                    var reason = bind(field.coordinate(), construction, children, constructions, records,
                        path, members);
                    if (reason != null) {
                        return reason;
                    }
                    continue;
                }
                return "input field " + String.join(".", path) + " names member " + field.boundName()
                    + ", which " + construction.className() + " does not have";
            }
            Optional<Build> nested = Optional.empty();
            if ("INPUT_OBJECT".equals(field.typeKind())) {
                var memberClass = slot.get().elementClass() != null ? slot.get().elementClass() : slot.get().erasedClass();
                var result = build(field.coordinate(), memberClass, children, constructions, records, List.of());
                if (result instanceof Result.Refused refused) {
                    return refused.reason();
                }
                nested = Optional.of(((Result.Built) result).build());
            }
            members.add(new Member(slot.get(), path, field.typeKind(), field.list(), nested));
        }
        return null;
    }
}
