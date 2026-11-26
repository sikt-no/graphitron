package no.sikt.graphitron.emit;

import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.when;

/**
 * The fill emitter's query: for every service call, what fills each of its method's parameters, in
 * position order, with what the fill names. An argument fill carries where it lands, as the names
 * from the argument down to the input field taken, what kind of type the value there is, and
 * whether it is a node id or a generated scalar's component, read off
 * {@code graphitron_occurrence_binding_extraction}, which states once how a value reaches its
 * parameter.
 *
 * <p>A call some position of which nothing fills is still read, marked incomplete, so a caller can
 * say which call it cannot emit rather than emit one with a parameter missing.
 */
public final class FillQuery {

    private FillQuery() {}

    /**
     * Where an argument fill lands: the path of names from the argument, the positions on it whose
     * value is a list, and what is there. Where the value reaches the parameter as a component of a
     * generated scalar, {@code component} is that component, as the binding's extraction names it,
     * and {@code decode} what the call decodes the scalar's value as.
     */
    public record Landing(List<String> path, Set<Integer> lists, String typeKind, boolean nodeId,
                          Optional<String> component, Optional<GeneratedScalarQuery.Decode> decode) {
        public Landing {
            path = List.copyOf(path);
            lists = Set.copyOf(lists);
            Objects.requireNonNull(typeKind, "typeKind");
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(decode, "decode");
        }

        /** A landing that hands over the value as it arrives. */
        public Landing(List<String> path, Set<Integer> lists, String typeKind, boolean nodeId) {
            this(path, lists, typeKind, nodeId, Optional.empty(), Optional.empty());
        }

        /** A landing no step of which is a list. */
        public Landing(List<String> path, String typeKind, boolean nodeId) {
            this(path, Set.of(), typeKind, nodeId);
        }
    }

    /**
     * One parameter and what fills it: the parameter's position, name and type as the store spells
     * it, the fill, and whatever the fill names.
     */
    public record Fill(int position, String parameterName, String parameterType, String erasedClass,
                       String fill, String contextKey, String sessionMember, Optional<Landing> landing) {
        public Fill {
            Objects.requireNonNull(parameterType, "parameterType");
            Objects.requireNonNull(fill, "fill");
            Objects.requireNonNull(landing, "landing");
        }
    }

    /**
     * One call: the producing occurrence, the declared field it is an occurrence of, its fills in
     * position order, and whether every position has one.
     */
    public record Row(String coordinate, String fieldCoordinate, List<Fill> fills, boolean complete) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(fieldCoordinate, "fieldCoordinate");
            fills = List.copyOf(fills);
        }
    }

    private record Raw(int position, String parameterName, String parameterType, String erasedClass,
                       String fill, String contextKey, String sessionMember, String landingCoordinate,
                       String typeKind, Boolean nodeId, String component) {}

    /** Where a landing lies under its argument: the names from the argument down, and which are lists. */
    private record LandingPath(List<String> names, Set<Integer> lists) {}

    /** Each argument and input-field occurrence's path from its argument down, by coordinate. */
    private static Map<String, LandingPath> landingPaths(StoreHandle store) {
        var st = GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
        var out = new LinkedHashMap<String, LandingPath>();
        store.dsl().select(st.COORDINATE, st.DEPTH, st.NAME, st.IS_LIST)
            .from(st)
            .where(st.GRAPH_NAME.eq(store.graphName()))
            .orderBy(st.COORDINATE, st.DEPTH)
            .forEach(r -> {
                var path = out.computeIfAbsent(r.value1(), k -> new LandingPath(new ArrayList<>(), new HashSet<>()));
                path.names().add(r.value3());
                if (r.value4()) path.lists().add(r.value2());
            });
        return out;
    }

    /**
     * A context key a call reads, a {@code @service}'s or a {@code @condition}'s: the key, the type of
     * the parameter it fills, which is what the call casts the request context's value to, and the
     * declared element and method making the call.
     */
    public record ContextKey(String key, TypeName type, String fieldCoordinate, String className,
                             String methodName) {
        public ContextKey {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * The context keys the graph's service and condition calls read, one per declared element, key,
     * parameter type and method, ordered by key, then type, then element, then method. A condition's
     * call is made once per table its occurrence binds, and each reads the same key; the distinct
     * read folds them.
     */
    public static List<ContextKey> contextKeys(StoreHandle store) {
        var s = GRAPHITRON_OCCURRENCE_CALL_METHOD;
        var o = GRAPHITRON_OCCURRENCE;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var p = CODE_METHOD_PARAMETER;
        return store.dsl()
            .selectDistinct(c.CONTEXT_KEY, p.PARAMETER_TYPE, o.ELEMENT_COORDINATE, s.METHOD_CLASS_NAME,
                s.METHOD_NAME)
            .from(s)
            .join(o).on(o.GRAPH_NAME.eq(s.GRAPH_NAME), o.COORDINATE.eq(s.COORDINATE))
            .join(c).on(c.GRAPH_NAME.eq(s.GRAPH_NAME), c.COORDINATE.eq(s.COORDINATE), c.CALL.eq(s.CALL))
            .join(p).on(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR),
                p.POSITION.eq(c.POSITION))
            .where(s.GRAPH_NAME.eq(store.graphName()), s.CALL_KIND.in("SERVICE", "CONDITION"),
                c.FILL.eq("CONTEXT_KEY"))
            .orderBy(c.CONTEXT_KEY, p.PARAMETER_TYPE, o.ELEMENT_COORDINATE, s.METHOD_CLASS_NAME,
                s.METHOD_NAME)
            .fetch(rec -> new ContextKey(rec.value1(), SessionIdentityRows.typeOf(rec.value2()), rec.value3(),
                rec.value4(), rec.value5()));
    }

    /** The graph's calls, by coordinate. */
    public static List<Row> read(StoreHandle store) {
        var s = GRAPHITRON_OCCURRENCE_CALL_METHOD;
        var o = GRAPHITRON_OCCURRENCE;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var p = CODE_METHOD_PARAMETER;
        var lo = GRAPHITRON_OCCURRENCE.as("lo");
        var la = GRAPHITRON_ARGUMENT;
        var lf = GRAPHITRON_FIELD;
        var lt = GRAPHITRON_TYPE;
        var b = GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
        var e = GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
        var graph = store.graphName();
        var paths = landingPaths(store);
        var decodes = GeneratedScalarQuery.decodes(store);
        var fills = multiset(
            select(c.POSITION, c.PARAMETER_NAME, p.PARAMETER_TYPE, p.ERASED_CLASS, c.FILL,
                c.CONTEXT_KEY, c.SESSION_MEMBER, c.LANDING_COORDINATE, lt.KIND,
                field(e.EXTRACTION.eq("NODE_ID_RECORD")), when(e.EXTRACTION.eq("SCALAR_COMPONENT"), e.COMPONENT))
                .from(c)
                .join(p).on(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                    p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR),
                    p.POSITION.eq(c.POSITION))
                // How the value reaches the parameter, as the store states it for the binding.
                .leftJoin(b).on(b.GRAPH_NAME.eq(c.GRAPH_NAME), b.CALL_COORDINATE.eq(c.COORDINATE),
                    b.CALL.eq(c.CALL), b.POSITION.eq(c.POSITION))
                .leftJoin(e).on(e.GRAPH_NAME.eq(b.GRAPH_NAME), e.COORDINATE.eq(b.COORDINATE), e.BOUND.eq(b.BOUND))
                // What the landing is: an argument's or an input field's declared type, and its kind.
                .leftJoin(lo).on(lo.GRAPH_NAME.eq(c.GRAPH_NAME), lo.COORDINATE.eq(c.LANDING_COORDINATE))
                .leftJoin(la).on(la.GRAPH_NAME.eq(lo.GRAPH_NAME), la.COORDINATE.eq(lo.ELEMENT_COORDINATE))
                .leftJoin(lf).on(lf.GRAPH_NAME.eq(lo.GRAPH_NAME), lf.COORDINATE.eq(lo.ELEMENT_COORDINATE))
                .leftJoin(lt).on(lt.GRAPH_NAME.eq(lo.GRAPH_NAME),
                    lt.TYPE_NAME.eq(coalesce(la.NAMED_TYPE, lf.NAMED_TYPE)))
                // Correlated to the service call, so another kind's fills never reach it.
                .where(c.GRAPH_NAME.eq(s.GRAPH_NAME), c.COORDINATE.eq(s.COORDINATE), c.CALL.eq(s.CALL))
                .orderBy(c.POSITION))
            .convertFrom(r -> r.map(Records.mapping(Raw::new)));
        var positions = field(selectCount().from(p)
            .where(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR)));
        return store.dsl()
            .select(s.COORDINATE, o.ELEMENT_COORDINATE, fills, positions)
            .from(s)
            .join(o).on(o.GRAPH_NAME.eq(s.GRAPH_NAME), o.COORDINATE.eq(s.COORDINATE))
            .where(s.GRAPH_NAME.eq(graph), s.CALL_KIND.eq("SERVICE"))
            .orderBy(s.COORDINATE)
            .fetch(rec -> new Row(rec.value1(), rec.value2(),
                rec.value3().stream().map(raw -> new Fill(raw.position(), raw.parameterName(),
                    raw.parameterType(), raw.erasedClass(), raw.fill(), raw.contextKey(),
                    raw.sessionMember(),
                    Optional.ofNullable(raw.landingCoordinate()).map(landing -> new Landing(
                        paths.get(landing).names(), paths.get(landing).lists(), raw.typeKind(),
                        Boolean.TRUE.equals(raw.nodeId()), Optional.ofNullable(raw.component()),
                        Optional.ofNullable(raw.component()).map(named -> decodes.get(landing))))))
                    .toList(),
                rec.value3().size() == rec.value4()));
    }
}
