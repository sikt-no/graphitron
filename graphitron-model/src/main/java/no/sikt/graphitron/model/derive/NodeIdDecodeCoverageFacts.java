package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.NodeIdDecodeCoordinate;
import no.sikt.graphitron.model.tables.GraphitronDirectiveApplication;
import no.sikt.graphitron.model.tables.GraphitronOccurrence;
import no.sikt.graphitron.model.tables.GraphqlArgumentElement;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Select;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_BINDING_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_PROJECTION;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The two store-side operands of the {@code @nodeId} decode-coverage rule: the census of authored
 * decoding instructions, and the coordinates the projected-key rail installs a decode at. Neither
 * is a detection; both are read for a rule whose other operand is the classification run's own
 * disposition ledger, and the rule is stated where the two meet.
 *
 * <p><b>Why the census and not a model of the decodes.</b> The obvious operand would be the
 * store's own model of which instructions have a decode, and it is the wrong one: presence there is
 * not presence of an emitted decode, the schema walk refusing coordinates such a model admits, and
 * it is a deep derived read a per-build rule has no business evaluating. What this reads instead is
 * the node id each argument and input field occurrence binds, a stored row, and where nothing binds,
 * an interface's own argument, the claim the author wrote; the question of what the generator did
 * about it is answered by the run rather than by a second model of the run.
 *
 * <p><b>Components, never the serialized use site.</b> Every coordinate is assembled from the
 * columns the relations state it in, the declared argument an occurrence is reached under and the
 * input fields of its descent, rather than from the {@code use_site} rendering beside them. A Java-side string built
 * to match that rendering is a second spelling of one value, and a miss between the two spellings
 * does not read as a miss: it reads as a dropped instruction and fails a build that should pass.
 */
public final class NodeIdDecodeCoverageFacts {

    private NodeIdDecodeCoverageFacts() {}

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(
        GRAPHITRON_OCCURRENCE_NODE_ID, GRAPHITRON_BINDING_CLAIM, GRAPHITRON_NODE,
        GRAPHITRON_DIRECTIVE_APPLICATION, GRAPHQL_ARGUMENT_ELEMENT, GRAPHQL_ARGUMENT,
        GRAPHQL_FIELD_ELEMENT, GRAPHQL_FIELD, GRAPHQL_ROOT_OPERATION, GRAPHITRON_TYPE,
        INTENT_TYPE_DOMAIN, GRAPHITRON_OCCURRENCE,
        GRAPHITRON_FIELD, GRAPHITRON_ARGUMENT, INTENT_RESOLVED_NODE_KEY_PROJECTION,
        GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION, GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER,
        GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE, GRAPHITRON_OCCURRENCE_CALL_PARAMETER);

    /**
     * One authored decoding instruction at one use site: where it is, what node type it decodes
     * against, and where the author wrote it. The unit the coverage rule subtracts the ledger from.
     */
    public record Instruction(NodeIdDecodeCoordinate coordinate, String nodeTypeName,
                              SourceLocation location) {}

    /**
     * What the store says about decode coverage for one graph: every decoding instruction inside
     * the classification domain, and the coordinates the projected-key rail installs at.
     */
    public record Facts(List<Instruction> census, Set<NodeIdDecodeCoordinate> projectedInstalls) {

        public Facts {
            census = List.copyOf(census);
            projectedInstalls = Set.copyOf(projectedInstalls);
        }

        /** The empty facts, for a caller running with no store behind it. */
        public static Facts empty() {
            return new Facts(List.of(), Set.of());
        }
    }

    /** Reads both operands over {@code graphName}'s partition. */
    public static Facts read(DSLContext dsl, String graphName) {
        var census = new java.util.ArrayList<Instruction>();
        census.addAll(argumentInstructions(dsl, graphName));
        census.addAll(interfaceArgumentInstructions(dsl, graphName));
        census.addAll(inputFieldInstructions(dsl, graphName));
        return new Facts(census, projectedInstalls(dsl, graphName));
    }

    /**
     * The argument-site census, off the node ids the occurrences bind. An argument is declared where
     * it is consumed, so its definition and its use site are one coordinate, its occurrences under
     * several parents folding onto it.
     */
    private static List<Instruction> argumentInstructions(DSLContext dsl, String graphName) {
        var k = GRAPHITRON_OCCURRENCE_NODE_ID;
        var o = GRAPHITRON_OCCURRENCE;
        var ae = GRAPHQL_ARGUMENT_ELEMENT;
        var a = GRAPHQL_ARGUMENT;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        return dsl.selectDistinct(ae.TYPE_NAME, ae.FIELD_NAME, ae.ARGUMENT_NAME, nodeTypeName(o),
                coalesce(da.SOURCE_NAME, a.SOURCE_NAME), coalesce(da.SOURCE_LINE, a.SOURCE_LINE),
                coalesce(da.SOURCE_COLUMN, a.SOURCE_COLUMN))
            .from(k)
            .join(o).on(o.GRAPH_NAME.eq(k.GRAPH_NAME), o.COORDINATE.eq(k.COORDINATE))
            .join(ae).on(ae.GRAPH_NAME.eq(o.GRAPH_NAME), ae.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(a).on(a.GRAPH_NAME.eq(ae.GRAPH_NAME), a.TYPE_NAME.eq(ae.TYPE_NAME),
                a.FIELD_NAME.eq(ae.FIELD_NAME), a.ARGUMENT_NAME.eq(ae.ARGUMENT_NAME))
            .leftJoin(da).on(nodeIdApplication(da, o))
            .where(k.GRAPH_NAME.eq(graphName), o.KIND.eq("FIELD_ARGUMENT"),
                inDomain(graphName, ae.TYPE_NAME), notExists(nodeLookup(ae)))
            .orderBy(ae.TYPE_NAME, ae.FIELD_NAME, ae.ARGUMENT_NAME)
            .fetch(row -> new Instruction(
                new NodeIdDecodeCoordinate.Argument(row.value1(), row.value2(), row.value3()),
                row.value4(), location(row.value5(), row.value6(), row.value7())));
    }

    /**
     * The arguments of node and nodes, a query-root field returning the Node interface, which the
     * node lookup decodes on a rail of its own: the producer graphitron_occurrence_producer states as
     * LOOKUP by that signature.
     */
    private static Select<?> nodeLookup(GraphqlArgumentElement ae) {
        var f = GRAPHQL_FIELD;
        var t = GRAPHITRON_TYPE;
        var q = GRAPHQL_ROOT_OPERATION;
        return selectOne().from(f)
            .join(t).on(t.GRAPH_NAME.eq(f.GRAPH_NAME), t.TYPE_NAME.eq(f.NAMED_TYPE),
                t.KIND.eq("INTERFACE"))
            .join(q).on(q.GRAPH_NAME.eq(f.GRAPH_NAME), q.TYPE_NAME.eq(f.TYPE_NAME),
                q.OPERATION.eq("QUERY"))
            .where(f.GRAPH_NAME.eq(ae.GRAPH_NAME), f.TYPE_NAME.eq(ae.TYPE_NAME),
                f.FIELD_NAME.eq(ae.FIELD_NAME), f.NAMED_TYPE.eq("Node"));
    }

    /**
     * A {@code @nodeId(typeName:)} an interface's field argument carries, where no occurrence reaches
     * the interface's own field: an implementation redeclares the argument and need not repeat the
     * directive, so the interface's declaration is the one place the author's instruction can be
     * named. Read off what was written, the claim, since nothing binds there.
     */
    private static List<Instruction> interfaceArgumentInstructions(DSLContext dsl, String graphName) {
        var cl = GRAPHITRON_BINDING_CLAIM;
        var ae = GRAPHQL_ARGUMENT_ELEMENT;
        var t = GRAPHITRON_TYPE;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        var o = GRAPHITRON_OCCURRENCE;
        return dsl.selectDistinct(ae.TYPE_NAME, ae.FIELD_NAME, ae.ARGUMENT_NAME, cl.NAMED,
                da.SOURCE_NAME, da.SOURCE_LINE, da.SOURCE_COLUMN)
            .from(cl)
            .join(ae).on(ae.GRAPH_NAME.eq(cl.GRAPH_NAME), ae.COORDINATE.eq(cl.COORDINATE))
            .join(t).on(t.GRAPH_NAME.eq(ae.GRAPH_NAME), t.TYPE_NAME.eq(ae.TYPE_NAME),
                t.KIND.eq("INTERFACE"))
            .join(da).on(da.GRAPH_NAME.eq(cl.GRAPH_NAME), da.COORDINATE.eq(cl.COORDINATE),
                da.DIRECTIVE_NAME.eq("nodeId"), da.ORDINAL.eq(0))
            .where(cl.GRAPH_NAME.eq(graphName), cl.KIND.eq("NODE_ID"), cl.NAMED.isNotNull(),
                inDomain(graphName, ae.TYPE_NAME),
                notExists(selectOne().from(o)
                    .where(o.GRAPH_NAME.eq(cl.GRAPH_NAME), o.ELEMENT_COORDINATE.eq(cl.COORDINATE))))
            .orderBy(ae.TYPE_NAME, ae.FIELD_NAME, ae.ARGUMENT_NAME)
            .fetch(row -> new Instruction(
                new NodeIdDecodeCoordinate.Argument(row.value1(), row.value2(), row.value3()),
                row.value4(), location(row.value5(), row.value6(), row.value7())));
    }

    /**
     * The input-field census, one row per use site. The declared argument an occurrence is reached
     * under and every input field of its descent come off the graph's input occurrences, read once,
     * and nothing here parses the coordinate. Distinct on the coordinate the ledger keys by:
     * occurrences of one input field under one declared argument, reached through different parents,
     * are one use site to the build.
     */
    private static List<Instruction> inputFieldInstructions(DSLContext dsl, String graphName) {
        var k = GRAPHITRON_OCCURRENCE_NODE_ID;
        var o = GRAPHITRON_OCCURRENCE;
        var fe = GRAPHQL_FIELD_ELEMENT;
        var f = GRAPHQL_FIELD;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        var occurrences = InputOccurrences.read(dsl, graphName);
        var census = new LinkedHashSet<Instruction>();
        dsl.selectDistinct(o.COORDINATE, nodeTypeName(o),
                coalesce(da.SOURCE_NAME, f.SOURCE_NAME), coalesce(da.SOURCE_LINE, f.SOURCE_LINE),
                coalesce(da.SOURCE_COLUMN, f.SOURCE_COLUMN))
            .from(k)
            .join(o).on(o.GRAPH_NAME.eq(k.GRAPH_NAME), o.COORDINATE.eq(k.COORDINATE))
            .join(fe).on(fe.GRAPH_NAME.eq(o.GRAPH_NAME), fe.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(fe.GRAPH_NAME), f.TYPE_NAME.eq(fe.TYPE_NAME),
                f.FIELD_NAME.eq(fe.FIELD_NAME))
            .leftJoin(da).on(nodeIdApplication(da, o))
            .where(k.GRAPH_NAME.eq(graphName), o.KIND.eq("INPUT_FIELD"))
            .orderBy(o.COORDINATE)
            .forEach(row -> {
                var coordinate = occurrences.coordinateOf(row.value1());
                // Scoped on the consuming coordinate's owning type rather than on the input type the
                // instruction is declared on: the consuming field is where a build error attaches and
                // what the generator's traversal either reaches or does not.
                if (coordinate != null && inDomain(dsl, graphName, coordinate.rootTypeName())) {
                    census.add(new Instruction(coordinate, row.value2(),
                        location(row.value3(), row.value4(), row.value5())));
                }
            });
        return List.copyOf(census);
    }

    /**
     * The type a decode at the occurrence names: an interface or union a {@code typeName:} wrote,
     * which binds once per member node, or else the node the binding decided.
     */
    private static Field<String> nodeTypeName(GraphitronOccurrence o) {
        var cl = GRAPHITRON_BINDING_CLAIM;
        var n = GRAPHITRON_NODE;
        return coalesce(
            field(select(cl.NAMED).from(cl)
                .where(cl.GRAPH_NAME.eq(o.GRAPH_NAME), cl.COORDINATE.eq(o.ELEMENT_COORDINATE),
                    cl.KIND.eq("NODE_ID"), notExists(selectOne().from(n)
                        .where(n.GRAPH_NAME.eq(cl.GRAPH_NAME), n.TYPE_NAME.eq(cl.NAMED))))
                .limit(1)),
            GRAPHITRON_OCCURRENCE_NODE_ID.NODE_TYPE_NAME);
    }

    /**
     * The written {@code @nodeId} on the occurrence's declaration, whose position the census reports;
     * an id named for its target has none and reports its declaration.
     */
    private static Condition nodeIdApplication(GraphitronDirectiveApplication da, GraphitronOccurrence o) {
        return da.GRAPH_NAME.eq(o.GRAPH_NAME).and(da.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .and(da.DIRECTIVE_NAME.eq("nodeId")).and(da.ORDINAL.eq(0));
    }

    /**
     * The coordinates the projected-key rail installs a decode at: an {@code argMapping} binding
     * that resolves a key column off a decoded id, at a site whose emitter reads one.
     *
     * <p>Read at the view's own {@code (site, use_site, position)} grain rather than through
     * {@link ResolvedKeyProjections}, which coarsens the key to {@code (type, field, path)} because
     * an emitter needs no more. Coarsening here would key the install at the definition while the
     * census is keyed at the use, and a definition-keyed install anti-joins away every use site
     * where nothing was installed: a silent miss of exactly the class this rule exists to close,
     * arriving through the keying axis.
     *
     * <p>Narrowed on {@link ArgmappingProjectionDefects#EMITTING_SITES} rather than a list of its
     * own, so what counts as an install here is the same declaration that decides which sites the
     * emitters are wired for. Presence in the view alone would not do: it resolves a projection at
     * every site, and what leaves only the emitting ones is that {@link ArgmappingProjectionDefects}
     * has already failed the build for the rest.
     *
     * <p>A {@code @service} parameter's projection is the store-fed emitter's, which decodes every slot
     * {@code graphitron_occurrence_node_id_shape} states a shape for, so its install is read there,
     * at the landing occurrence, and the walk-era relation is not asked about that site.
     */
    private static Set<NodeIdDecodeCoordinate> projectedInstalls(DSLContext dsl, String graphName) {
        var v = INTENT_RESOLVED_NODE_KEY_PROJECTION;
        var out = new LinkedHashSet<NodeIdDecodeCoordinate>();
        var occurrences = InputOccurrences.read(dsl, graphName);
        var walkSites = java.util.EnumSet.copyOf(ArgmappingProjectionDefects.EMITTING_SITES);
        walkSites.remove(ArgmappingProjectionDefects.Site.SERVICE);
        dsl.selectDistinct(v.BOUND_KIND, v.BOUND_TYPE_NAME, v.BOUND_FIELD_NAME,
                v.BOUND_ARGUMENT_NAME, v.TYPE_NAME, v.FIELD_NAME)
            .from(v)
            .where(v.GRAPH_NAME.eq(graphName), ArgmappingProjectionDefects.Site.anyOf(
                walkSites, v.DIRECTIVE_NAME, v.ELEMENT_KIND))
            .forEach(row -> {
                if ("ARGUMENT".equals(row.value1())) {
                    out.add(new NodeIdDecodeCoordinate.Argument(
                        row.value2(), row.value3(), row.value4()));
                } else {
                    out.addAll(inputFieldUseSites(occurrences, row.value5(), row.value6(),
                        row.value2(), row.value3()));
                }
            });
        var pj = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION;
        var sp = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
        var d = GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE;
        var cp = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var o = GRAPHITRON_OCCURRENCE;
        var ra = GRAPHITRON_ARGUMENT;
        dsl.selectDistinct(o.COORDINATE, o.KIND, ra.TYPE_NAME, ra.FIELD_NAME, ra.ARGUMENT_NAME)
            .from(pj)
            .join(sp).on(sp.GRAPH_NAME.eq(pj.GRAPH_NAME), sp.CALL_COORDINATE.eq(pj.CALL_COORDINATE),
                sp.CALL.eq(pj.CALL), sp.POSITION.eq(pj.POSITION))
            .join(cp).on(cp.GRAPH_NAME.eq(sp.GRAPH_NAME), cp.COORDINATE.eq(sp.CALL_COORDINATE),
                cp.CALL.eq(sp.CALL), cp.POSITION.eq(sp.POSITION), cp.CALL_KIND.eq("SERVICE"))
            .join(d).on(d.GRAPH_NAME.eq(sp.GRAPH_NAME), d.COORDINATE.eq(sp.COORDINATE), d.SLOT.eq(sp.SLOT),
                d.BOUND.eq(pj.BOUND))
            .join(o).on(o.GRAPH_NAME.eq(pj.GRAPH_NAME), o.COORDINATE.eq(pj.COORDINATE))
            .leftJoin(ra).on(ra.GRAPH_NAME.eq(o.GRAPH_NAME), ra.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(pj.GRAPH_NAME.eq(graphName))
            .forEach(row -> {
                if ("FIELD_ARGUMENT".equals(row.value2())) {
                    out.add(new NodeIdDecodeCoordinate.Argument(row.value3(), row.value4(), row.value5()));
                } else if (occurrences.coordinateOf(row.value1()) != null) {
                    out.add(occurrences.coordinateOf(row.value1()));
                }
            });
        return out;
    }

    /**
     * The use sites an {@code INPUT_FIELD} binding installs at: every occurrence of the bound input
     * field under the spelling site's own field.
     *
     * <p>The root argument is the one component the binding relation cannot state, being NULL on an
     * {@code INPUT_FIELD} binding in {@code graphitron_argmapping_match}, so it comes from the
     * occurrence the way every other descent component does.
     *
     * <p>Where one field reaches one input field through two arguments, both are taken as installed.
     * That is a deliberate coarsening in the safe direction: it can cover a sibling argument the
     * emitter does not in fact install at, which loses a report, and it can never uncover an install,
     * which would fail a build that should pass.
     */
    private static List<NodeIdDecodeCoordinate> inputFieldUseSites(
            InputOccurrences occurrences, String rootTypeName, String rootFieldName,
            String containerTypeName, String fieldName) {
        return occurrences.of(containerTypeName, fieldName).stream()
            .filter(c -> c.rootTypeName().equals(rootTypeName) && c.rootFieldName().equals(rootFieldName))
            .<NodeIdDecodeCoordinate>map(c -> c)
            .toList();
    }

    /**
     * The graph's input-field occurrences as coordinates: per occurrence, the declared argument it is
     * reached under and its descent, the input fields from the argument down in order, read off the
     * parent links.
     */
    private record InputOccurrences(Map<String, NodeIdDecodeCoordinate.InputField> byCoordinate) {

        static InputOccurrences read(DSLContext dsl, String graphName) {
            var o = GRAPHITRON_OCCURRENCE;
            var f = GRAPHITRON_FIELD;
            var ao = GRAPHITRON_OCCURRENCE.as("ao");
            var ra = GRAPHITRON_ARGUMENT;
            record Row(String parent, NodeIdDecodeCoordinate.Step step, String rootType,
                       String rootField, String rootArgument) {}
            var rows = new HashMap<String, Row>();
            dsl.select(o.COORDINATE, o.PARENT_COORDINATE, f.TYPE_NAME, f.FIELD_NAME,
                    ra.TYPE_NAME, ra.FIELD_NAME, ra.ARGUMENT_NAME)
                .from(o)
                .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
                .join(ao).on(ao.GRAPH_NAME.eq(o.GRAPH_NAME), ao.COORDINATE.eq(o.ARGUMENT_COORDINATE))
                .join(ra).on(ra.GRAPH_NAME.eq(ao.GRAPH_NAME), ra.COORDINATE.eq(ao.ELEMENT_COORDINATE))
                .where(o.GRAPH_NAME.eq(graphName), o.KIND.eq("INPUT_FIELD"))
                .forEach(r -> rows.put(r.value1(), new Row(r.value2(),
                    new NodeIdDecodeCoordinate.Step(r.value3(), r.value4()),
                    r.value5(), r.value6(), r.value7())));
            var byCoordinate = new LinkedHashMap<String, NodeIdDecodeCoordinate.InputField>();
            rows.forEach((coordinate, row) -> {
                var descent = new ArrayList<NodeIdDecodeCoordinate.Step>();
                for (var at = row; at != null; at = rows.get(at.parent())) {
                    descent.addFirst(at.step());
                }
                byCoordinate.put(coordinate, new NodeIdDecodeCoordinate.InputField(
                    row.rootType(), row.rootField(), row.rootArgument(), descent));
            });
            return new InputOccurrences(byCoordinate);
        }

        NodeIdDecodeCoordinate.InputField coordinateOf(String coordinate) {
            return byCoordinate.get(coordinate);
        }

        /** The distinct use sites of one declared input field, in no particular order. */
        List<NodeIdDecodeCoordinate.InputField> of(String containerTypeName, String fieldName) {
            return byCoordinate.values().stream()
                .filter(c -> {
                    var last = c.descent().getLast();
                    return last.containerTypeName().equals(containerTypeName)
                        && last.fieldName().equals(fieldName);
                })
                .distinct()
                .toList();
        }
    }

    /**
     * The build-error consumer's population: the coordinate's owning type is a member of the
     * classification domain: only a coordinate the generator intends to classify can fail a build.
     */
    private static Condition inDomain(String graphName, Field<String> typeName) {
        var d = INTENT_TYPE_DOMAIN;
        return exists(selectOne().from(d)
            .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(typeName)));
    }

    /** {@link #inDomain(String, Field)} for a type name already in hand. */
    private static boolean inDomain(DSLContext dsl, String graphName, String typeName) {
        var d = INTENT_TYPE_DOMAIN;
        return dsl.fetchExists(d, d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(typeName));
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
