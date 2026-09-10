package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.NodeIdDecodeCoordinate;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.Table;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH_STEP;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_INSTRUCTION;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_PROJECTION;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The two store-side operands of the {@code @nodeId} decode-coverage rule: the census of authored
 * decoding instructions, and the coordinates the projected-key rail installs a decode at. Neither
 * is a detection; both are read for a rule whose other operand is the classification run's own
 * disposition ledger, and the rule is stated where the two meet.
 *
 * <p><b>Why the census and not {@code intent_node_id_decode}.</b> The obvious operand would be the
 * store's own model of which instructions have a decode, and it is the wrong one in both
 * directions. It over-claims: a cross-table {@code @nodeId} filter with no foreign key joining the
 * pair draws a decode row while the schema walk refuses the coordinate outright, so presence there
 * is not presence of an emitted decode. And it is the deepest derived read in the schema, which a
 * per-build rule has no business evaluating. What this reads instead is the flat, materialized
 * statement of what the author <em>wrote</em>, and the question of what the generator did about it
 * is answered by the run rather than by a second model of the run.
 *
 * <p><b>Components, never the serialized use site.</b> Every coordinate is assembled from the
 * columns the relations state it in, the occurrence path's three root columns and its ordinal-keyed
 * step child, rather than from the {@code use_site} rendering beside them. A Java-side string built
 * to match that rendering is a second spelling of one value, and a miss between the two spellings
 * does not read as a miss: it reads as a dropped instruction and fails a build that should pass.
 */
public final class NodeIdDecodeCoverageFacts {

    private NodeIdDecodeCoverageFacts() {}

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(
        INTENT_NODE_ID_INSTRUCTION, INTENT_TYPE_DOMAIN, INTENT_INPUT_OCCURRENCE_PATH,
        INTENT_INPUT_OCCURRENCE_PATH_STEP, INTENT_RESOLVED_NODE_KEY_PROJECTION);

    /**
     * The {@code site} values whose emitters read a resolved key projection, mirroring
     * {@link ArgmappingProjectionDefects#EMITTING_SITES} rather than restating it. The same
     * declaration, so a site joining that set joins this rail's population in the same edit: read
     * off a second list here, this rule would go quiet exactly where the new emitter starts
     * installing decodes.
     */
    private static final Set<String> PROJECTED_INSTALL_SITES =
        ArgmappingProjectionDefects.EMITTING_SITES.stream().map(Enum::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

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
        census.addAll(inputFieldInstructions(dsl, graphName));
        return new Facts(census, projectedInstalls(dsl, graphName));
    }

    /**
     * The argument-site census. An argument is declared where it is consumed, so its definition and
     * its use site are one coordinate and no occurrence path is involved.
     */
    private static List<Instruction> argumentInstructions(DSLContext dsl, String graphName) {
        var i = INTENT_NODE_ID_INSTRUCTION;
        return dsl.select(i.TYPE_NAME, i.FIELD_NAME, i.ARGUMENT_NAME, i.RESOLVED_TYPE_NAME,
                i.SOURCE_NAME, i.SOURCE_LINE, i.SOURCE_COLUMN)
            .from(i)
            .where(i.GRAPH_NAME.eq(graphName), i.SITE.eq("ARGUMENT"), inDomain(graphName, i.TYPE_NAME))
            .orderBy(i.TYPE_NAME, i.FIELD_NAME, i.ARGUMENT_NAME)
            .fetch(row -> new Instruction(
                new NodeIdDecodeCoordinate.Argument(row.value1(), row.value2(), row.value3()),
                row.value4(), location(row.value5(), row.value6(), row.value7())));
    }

    /**
     * The input-field census, one row per use site. The instruction's own {@code path} is the
     * occurrence path's key, so the use site's three root columns and every step of the descent are
     * one join away and nothing here parses the key: the steps arrive as a correlated collection on
     * their own {@code (path, ordinal)} grain, which is what keeps one instruction one row however
     * deep its descent.
     */
    private static List<Instruction> inputFieldInstructions(DSLContext dsl, String graphName) {
        var i = INTENT_NODE_ID_INSTRUCTION;
        var p = INTENT_INPUT_OCCURRENCE_PATH;
        return dsl.select(p.ROOT_TYPE_NAME, p.ROOT_FIELD_NAME, p.ROOT_ARGUMENT_NAME,
                descentOf(p.GRAPH_NAME, p.PATH), i.RESOLVED_TYPE_NAME,
                i.SOURCE_NAME, i.SOURCE_LINE, i.SOURCE_COLUMN)
            .from(i)
            .join(p).on(p.GRAPH_NAME.eq(i.GRAPH_NAME), p.PATH.eq(i.PATH))
            .where(i.GRAPH_NAME.eq(graphName), i.SITE.eq("INPUT_FIELD"),
                // Scoped on the consuming coordinate's owning type rather than on the input type
                // the instruction is declared on: the consuming field is where a build error
                // attaches and what the generator's traversal either reaches or does not.
                inDomain(graphName, p.ROOT_TYPE_NAME))
            .orderBy(p.ROOT_TYPE_NAME, p.ROOT_FIELD_NAME, p.ROOT_ARGUMENT_NAME, p.PATH)
            .fetch(row -> new Instruction(
                new NodeIdDecodeCoordinate.InputField(row.value1(), row.value2(), row.value3(),
                    row.value4()),
                row.value5(), location(row.value6(), row.value7(), row.value8())));
    }

    /** One occurrence path's steps, in descent order, as the coordinate's own component list. */
    private static Field<List<NodeIdDecodeCoordinate.Step>> descentOf(
            Field<String> graphName, Field<String> path) {
        var st = INTENT_INPUT_OCCURRENCE_PATH_STEP;
        return multiset(
            select(st.CONTAINER_TYPE_NAME, st.FIELD_NAME)
                .from(st)
                .where(st.GRAPH_NAME.eq(graphName), st.PATH.eq(path))
                .orderBy(st.ORDINAL))
            .convertFrom(r -> r.map(Records.mapping(NodeIdDecodeCoordinate.Step::new)));
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
     * <p>Narrowed on {@link #PROJECTED_INSTALL_SITES}, so what counts as an install here is the
     * same declaration that decides which sites the emitters are wired for. Presence in the view
     * alone would not do: it resolves a projection at every site, and what leaves only the emitting
     * ones is that {@link ArgmappingProjectionDefects} has already failed the build for the rest.
     */
    private static Set<NodeIdDecodeCoordinate> projectedInstalls(DSLContext dsl, String graphName) {
        var v = INTENT_RESOLVED_NODE_KEY_PROJECTION;
        var out = new LinkedHashSet<NodeIdDecodeCoordinate>();
        dsl.selectDistinct(v.BOUND_KIND, v.BOUND_TYPE_NAME, v.BOUND_FIELD_NAME,
                v.BOUND_ARGUMENT_NAME, v.TYPE_NAME, v.FIELD_NAME)
            .from(v)
            .where(v.GRAPH_NAME.eq(graphName), v.SITE.in(PROJECTED_INSTALL_SITES))
            .forEach(row -> {
                if ("ARGUMENT".equals(row.value1())) {
                    out.add(new NodeIdDecodeCoordinate.Argument(
                        row.value2(), row.value3(), row.value4()));
                } else {
                    out.addAll(inputFieldUseSites(dsl, graphName, row.value5(), row.value6(),
                        row.value2(), row.value3()));
                }
            });
        return out;
    }

    /**
     * The use sites an {@code INPUT_FIELD} binding installs at: every occurrence path under the
     * spelling site's own field whose last step is the bound input field.
     *
     * <p>The root argument is the one component the binding relation cannot state, being NULL on an
     * {@code INPUT_FIELD} binding in {@code graphitron_argmapping_match}, so it comes from the
     * occurrence path the way every other descent component does. Recovering it by splitting the
     * written path instead would be a second spelling of a resolution the store already states.
     *
     * <p>Where one field reaches one input field through two arguments, both paths are taken as
     * installed. That is a deliberate coarsening in the safe direction: it can cover a sibling
     * argument the emitter does not in fact install at, which loses a report, and it can never
     * uncover an install, which would fail a build that should pass.
     */
    private static List<NodeIdDecodeCoordinate> inputFieldUseSites(
            DSLContext dsl, String graphName, String rootTypeName, String rootFieldName,
            String containerTypeName, String fieldName) {
        var p = INTENT_INPUT_OCCURRENCE_PATH;
        var st = INTENT_INPUT_OCCURRENCE_PATH_STEP;
        return dsl.select(p.ROOT_ARGUMENT_NAME, descentOf(p.GRAPH_NAME, p.PATH))
            .from(p)
            .join(st).on(st.GRAPH_NAME.eq(p.GRAPH_NAME), st.PATH.eq(p.PATH),
                st.ORDINAL.eq(p.DEPTH))
            .where(p.GRAPH_NAME.eq(graphName), p.ROOT_TYPE_NAME.eq(rootTypeName),
                p.ROOT_FIELD_NAME.eq(rootFieldName),
                st.CONTAINER_TYPE_NAME.eq(containerTypeName), st.FIELD_NAME.eq(fieldName))
            .fetch(row -> new NodeIdDecodeCoordinate.InputField(
                rootTypeName, rootFieldName, row.value1(), row.value2()));
    }

    /**
     * The build-error consumer's population: the coordinate's owning type is a member of the
     * classification domain. The same predicate {@link NodeIdDecodeDefects} scopes its verdicts by,
     * and for the same reason: only a coordinate the generator intends to classify can fail a
     * build.
     */
    private static org.jooq.Condition inDomain(String graphName, Field<String> typeName) {
        var d = INTENT_TYPE_DOMAIN;
        return exists(selectOne().from(d)
            .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(typeName)));
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
