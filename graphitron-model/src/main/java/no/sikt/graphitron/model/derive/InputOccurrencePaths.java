package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH_STEP;
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code intent_input_occurrence_path} and its step child: every
 * occurrence of the input surface under a use site, seeded from arguments whose named type is
 * an input object and expanded through input-object-typed fields. Runs inside capture's own
 * transaction after the flush, clears the run's graph partition first (the cadence doctrine's
 * clearing rule, steps before paths for the foreign key), and re-derives. Materialized for the
 * same reason the classification domain is materialized: cyclic input nesting is legal GraphQL and H2
 * has no safe recursive view form over a cyclic graph.
 *
 * <p>The expansion is depth-stratified: pass {@code d} descends only from paths at depth
 * {@code d}, and a path descends only when its leaf type has kind {@code INPUT_OBJECT} and is
 * not already visited at an earlier position on the path (the root input type, or any earlier
 * step's named type). That is the classification walk's own first-visit guard
 * ({@code ClassifyContext.expandingTypes}) restated relationally, so the row population equals
 * the recursion tree the build already walks; the cycle-closing occurrence itself gets a row
 * (the walk classifies that field too, rejecting the circular reference) and simply never
 * expands. Each pass inserts the child paths, the child copies of the parent's step rows, and
 * one leaf step per child, all as {@code INSERT..SELECT} over the same parent-field join; the
 * serialized key is only ever constructed here, never parsed. The pass count is bounded by the
 * graph's input-object type count, enforced so a non-monotone edit fails loudly.
 */
public final class InputOccurrencePaths {

    private InputOccurrencePaths() {}

    /** Clears and re-derives the graph's occurrence-path partition; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        dsl.deleteFrom(INTENT_INPUT_OCCURRENCE_PATH_STEP)
            .where(INTENT_INPUT_OCCURRENCE_PATH_STEP.GRAPH_NAME.eq(graphName))
            .execute();
        dsl.deleteFrom(INTENT_INPUT_OCCURRENCE_PATH)
            .where(INTENT_INPUT_OCCURRENCE_PATH.GRAPH_NAME.eq(graphName))
            .execute();
        seed(dsl, graphName);
        int bound = 1 + dsl.fetchCount(GRAPHQL_TYPE,
            GRAPHQL_TYPE.GRAPH_NAME.eq(graphName).and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")));
        for (int depth = 0; expand(dsl, graphName, depth) > 0; depth++) {
            copyParentSteps(dsl, graphName, depth + 1);
            insertLeafSteps(dsl, graphName, depth + 1);
            if (depth > bound) {
                throw new IllegalStateException(
                    "intent_input_occurrence_path expansion for graph '" + graphName
                        + "' did not converge in " + bound
                        + " passes; the first-visit guard has stopped being effective");
            }
        }
    }

    /**
     * Depth-0 rows: one occurrence per argument whose named type is a captured input object.
     * The path is {@code <type>.<field>(<argument>)} and the leaf type is the argument's own.
     */
    private static void seed(DSLContext dsl, String graphName) {
        var p = INTENT_INPUT_OCCURRENCE_PATH;
        dsl.insertInto(p, p.GRAPH_NAME, p.PATH, p.ROOT_TYPE_NAME, p.ROOT_FIELD_NAME,
                p.ROOT_ARGUMENT_NAME, p.ROOT_INPUT_TYPE, p.LEAF_NAMED_TYPE, p.DEPTH)
            .select(dsl.select(GRAPHQL_ARGUMENT.GRAPH_NAME,
                    concat(GRAPHQL_ARGUMENT.TYPE_NAME, val("."), GRAPHQL_ARGUMENT.FIELD_NAME,
                        val("("), GRAPHQL_ARGUMENT.ARGUMENT_NAME, val(")")),
                    GRAPHQL_ARGUMENT.TYPE_NAME, GRAPHQL_ARGUMENT.FIELD_NAME,
                    GRAPHQL_ARGUMENT.ARGUMENT_NAME, GRAPHQL_ARGUMENT.NAMED_TYPE,
                    GRAPHQL_ARGUMENT.NAMED_TYPE, val(0))
                .from(GRAPHQL_ARGUMENT)
                .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPHQL_ARGUMENT.GRAPH_NAME)
                    .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_ARGUMENT.NAMED_TYPE))
                    .and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")))
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName)))
            .execute();
    }

    /**
     * One descent pass: every field of each expandable depth-{@code d} path's leaf type becomes
     * a depth-{@code d+1} path. Expandable means the leaf type has kind {@code INPUT_OBJECT}
     * and is the path's first visit of that type.
     */
    private static int expand(DSLContext dsl, String graphName, int depth) {
        var p = INTENT_INPUT_OCCURRENCE_PATH;
        var s = INTENT_INPUT_OCCURRENCE_PATH_STEP;
        return dsl.insertInto(p, p.GRAPH_NAME, p.PATH, p.ROOT_TYPE_NAME, p.ROOT_FIELD_NAME,
                p.ROOT_ARGUMENT_NAME, p.ROOT_INPUT_TYPE, p.LEAF_NAMED_TYPE, p.DEPTH)
            .select(dsl.select(p.GRAPH_NAME,
                    concat(p.PATH, val("/"), GRAPHQL_FIELD.FIELD_NAME),
                    p.ROOT_TYPE_NAME, p.ROOT_FIELD_NAME, p.ROOT_ARGUMENT_NAME,
                    p.ROOT_INPUT_TYPE, GRAPHQL_FIELD.NAMED_TYPE, val(depth + 1))
                .from(p)
                .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(p.GRAPH_NAME)
                    .and(GRAPHQL_TYPE.TYPE_NAME.eq(p.LEAF_NAMED_TYPE))
                    .and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")))
                .join(GRAPHQL_FIELD).on(GRAPHQL_FIELD.GRAPH_NAME.eq(p.GRAPH_NAME)
                    .and(GRAPHQL_FIELD.TYPE_NAME.eq(p.LEAF_NAMED_TYPE)))
                .where(p.GRAPH_NAME.eq(graphName))
                .and(p.DEPTH.eq(depth))
                .and(p.DEPTH.eq(0)
                    .or(p.LEAF_NAMED_TYPE.ne(p.ROOT_INPUT_TYPE)
                        .and(notExists(selectOne().from(s)
                            .where(s.GRAPH_NAME.eq(p.GRAPH_NAME))
                            .and(s.PATH.eq(p.PATH))
                            .and(s.ORDINAL.lt(p.DEPTH))
                            .and(s.NAMED_TYPE.eq(p.LEAF_NAMED_TYPE)))))))
            .execute();
    }

    /**
     * The new depth's children inherit their parent's step rows, re-keyed to the child path.
     * The parent-child relation is reconstructed the same way {@link #expand} built the child
     * key (parent path + '/' + a field of the parent's leaf type); the key is constructed,
     * never parsed.
     */
    private static void copyParentSteps(DSLContext dsl, String graphName, int newDepth) {
        var child = INTENT_INPUT_OCCURRENCE_PATH.as("child");
        var parent = INTENT_INPUT_OCCURRENCE_PATH.as("parent");
        var parentStep = INTENT_INPUT_OCCURRENCE_PATH_STEP.as("parent_step");
        var s = INTENT_INPUT_OCCURRENCE_PATH_STEP;
        dsl.insertInto(s, s.GRAPH_NAME, s.PATH, s.ORDINAL,
                s.CONTAINER_TYPE_NAME, s.FIELD_NAME, s.NAMED_TYPE)
            .select(dsl.select(child.GRAPH_NAME, child.PATH, parentStep.ORDINAL,
                    parentStep.CONTAINER_TYPE_NAME, parentStep.FIELD_NAME, parentStep.NAMED_TYPE)
                .from(child)
                .join(parent).on(parent.GRAPH_NAME.eq(child.GRAPH_NAME)
                    .and(parent.DEPTH.eq(newDepth - 1)))
                .join(GRAPHQL_FIELD).on(GRAPHQL_FIELD.GRAPH_NAME.eq(child.GRAPH_NAME)
                    .and(GRAPHQL_FIELD.TYPE_NAME.eq(parent.LEAF_NAMED_TYPE))
                    .and(concat(parent.PATH, val("/"), GRAPHQL_FIELD.FIELD_NAME).eq(child.PATH)))
                .join(parentStep).on(parentStep.GRAPH_NAME.eq(parent.GRAPH_NAME)
                    .and(parentStep.PATH.eq(parent.PATH)))
                .where(child.GRAPH_NAME.eq(graphName))
                .and(child.DEPTH.eq(newDepth)))
            .execute();
    }

    /** Each new child gets its own leaf step at ordinal = its depth. */
    private static void insertLeafSteps(DSLContext dsl, String graphName, int newDepth) {
        var child = INTENT_INPUT_OCCURRENCE_PATH.as("child");
        var parent = INTENT_INPUT_OCCURRENCE_PATH.as("parent");
        var s = INTENT_INPUT_OCCURRENCE_PATH_STEP;
        dsl.insertInto(s, s.GRAPH_NAME, s.PATH, s.ORDINAL,
                s.CONTAINER_TYPE_NAME, s.FIELD_NAME, s.NAMED_TYPE)
            .select(dsl.select(child.GRAPH_NAME, child.PATH, child.DEPTH,
                    parent.LEAF_NAMED_TYPE, GRAPHQL_FIELD.FIELD_NAME, child.LEAF_NAMED_TYPE)
                .from(child)
                .join(parent).on(parent.GRAPH_NAME.eq(child.GRAPH_NAME)
                    .and(parent.DEPTH.eq(newDepth - 1)))
                .join(GRAPHQL_FIELD).on(GRAPHQL_FIELD.GRAPH_NAME.eq(child.GRAPH_NAME)
                    .and(GRAPHQL_FIELD.TYPE_NAME.eq(parent.LEAF_NAMED_TYPE))
                    .and(concat(parent.PATH, val("/"), GRAPHQL_FIELD.FIELD_NAME).eq(child.PATH)))
                .where(child.GRAPH_NAME.eq(graphName))
                .and(child.DEPTH.eq(newDepth)))
            .execute();
    }
}
