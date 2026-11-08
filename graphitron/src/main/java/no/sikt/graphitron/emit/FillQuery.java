package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectOne;

/**
 * The fill emitter's query: for every service call, what fills each of its method's parameters, in
 * position order, with what the fill names. An argument fill carries where it lands, as the names
 * from the argument down to the input field taken, what kind of type the value there is, and
 * whether it is a node id: the three things that decide whether the wire value is passed as it is
 * or has to be built into the parameter's type first.
 *
 * <p>A call some position of which nothing fills is still read, marked incomplete, so a caller can
 * say which call it cannot emit rather than emit one with a parameter missing.
 */
public final class FillQuery {

    private FillQuery() {}

    /**
     * Where an argument fill lands: the path of names from the argument, the positions on it whose
     * value is a list, and what is there.
     */
    public record Landing(List<String> path, Set<Integer> lists, String typeKind, boolean nodeId) {
        public Landing {
            path = List.copyOf(path);
            lists = Set.copyOf(lists);
            Objects.requireNonNull(typeKind, "typeKind");
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
                       String typeKind, boolean nodeId) {}

    /** The graph's calls, by coordinate. */
    public static List<Row> read(StoreHandle store) {
        var s = GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
        var o = GRAPHITRON_OCCURRENCE;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var p = CODE_METHOD_PARAMETER;
        var lo = GRAPHITRON_OCCURRENCE.as("lo");
        var la = GRAPHITRON_ARGUMENT;
        var lf = GRAPHITRON_FIELD;
        var lt = GRAPHITRON_TYPE;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var graph = store.graphName();
        var paths = ArgumentPaths.read(store);
        var fills = multiset(
            select(c.POSITION, c.PARAMETER_NAME, p.PARAMETER_TYPE, p.ERASED_CLASS, c.FILL,
                c.CONTEXT_KEY, c.SESSION_MEMBER, c.LANDING_COORDINATE, lt.KIND,
                field(exists(selectOne().from(n)
                    .where(n.GRAPH_NAME.eq(c.GRAPH_NAME), n.COORDINATE.eq(c.LANDING_COORDINATE)))))
                .from(c)
                .join(p).on(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                    p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR),
                    p.POSITION.eq(c.POSITION))
                // What the landing is: an argument's or an input field's declared type, and its kind.
                .leftJoin(lo).on(lo.GRAPH_NAME.eq(c.GRAPH_NAME), lo.COORDINATE.eq(c.LANDING_COORDINATE))
                .leftJoin(la).on(la.GRAPH_NAME.eq(lo.GRAPH_NAME), la.COORDINATE.eq(lo.ELEMENT_COORDINATE))
                .leftJoin(lf).on(lf.GRAPH_NAME.eq(lo.GRAPH_NAME), lf.COORDINATE.eq(lo.ELEMENT_COORDINATE))
                .leftJoin(lt).on(lt.GRAPH_NAME.eq(lo.GRAPH_NAME),
                    lt.TYPE_NAME.eq(coalesce(la.NAMED_TYPE, lf.NAMED_TYPE)))
                .where(c.GRAPH_NAME.eq(s.GRAPH_NAME), c.COORDINATE.eq(s.COORDINATE))
                .orderBy(c.POSITION))
            .convertFrom(r -> r.map(Records.mapping(Raw::new)));
        var positions = field(selectCount().from(p)
            .where(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR)));
        return store.dsl()
            .select(s.COORDINATE, o.ELEMENT_COORDINATE, fills, positions)
            .from(s)
            .join(o).on(o.GRAPH_NAME.eq(s.GRAPH_NAME), o.COORDINATE.eq(s.COORDINATE))
            .where(s.GRAPH_NAME.eq(graph))
            .orderBy(s.COORDINATE)
            .fetch(rec -> new Row(rec.value1(), rec.value2(),
                rec.value3().stream().map(raw -> new Fill(raw.position(), raw.parameterName(),
                    raw.parameterType(), raw.erasedClass(), raw.fill(), raw.contextKey(),
                    raw.sessionMember(),
                    Optional.ofNullable(raw.landingCoordinate()).map(landing -> new Landing(
                        paths.get(landing).names(), paths.get(landing).lists(), raw.typeKind(),
                        raw.nodeId()))))
                    .toList(),
                rec.value3().size() == rec.value4()));
    }
}
