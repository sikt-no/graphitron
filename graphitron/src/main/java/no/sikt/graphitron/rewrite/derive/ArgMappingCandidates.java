package no.sikt.graphitron.rewrite.derive;

import org.jooq.DSLContext;

import java.util.ArrayList;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import no.sikt.graphitron.model.tables.GraphitronArgmappingCandidate;
import org.jooq.Condition;
import org.jooq.Record10;
import org.jooq.SelectJoinStep;

import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of {@code graphitron_argmapping_candidate}: everything an argMapping
 * right-hand side may name under a field, as a tree. Runs inside capture's own transaction after
 * the flush, clears the run's graph partition first, and re-derives. Stored rather than stated as
 * a view for {@link InputOccurrencePaths}'s reason: the descent is recursive and a view has no
 * safe recursive form over it.
 *
 * <p>Seeded from every argument rather than only the input-object-typed ones, which is the whole
 * difference from the occurrence-path relation beside it. A bare name with no dots is a legal
 * right-hand side and is what an entry with no {@code argMapping} binds to implicitly, so a scalar
 * argument is a candidate and needs a root row; a relation holding only the arguments that descend
 * covers a different population, and that mismatch is why the argMapping rules walked positions
 * instead of joining.
 *
 * <p>Expansion is depth-stratified and stops at the first revisit of a type already on the
 * candidate's own ancestry. Cyclic input nesting is legal GraphQL and does reach capture, which
 * runs before anything classifies: the classifier refuses the field that closes a cycle, but the
 * types are captured either way. The closing element is nameable, so it gets a row and is marked
 * as what it is, and nothing below it is written. Marking it rather than merely stopping is the
 * point: a candidate with no children is otherwise ambiguous between a leaf and a stopping point,
 * and a relation whose absences carry meaning is the shape this whole line of work exists to
 * remove. With the marker present the next pass's guard is a column test rather than a second
 * ancestry walk, and the deferral is a query anyone can write instead of a special case anyone has
 * to remember.
 *
 * <p>The ancestry test is a join chain rather than a lookup in a closure relation, which is the one
 * price this shape pays. A candidate carries its parent and not its ancestors, so pass {@code d}
 * walks {@code d} parent links to reach the roots; that is bounded by the same pass bound as the
 * expansion, and it buys a single relation where the neighbour needs two. The pass bound itself is
 * an invariant check: with the guard in place the expansion is monotone, so if it ever fires the
 * guard has stopped working and failing is the only correct outcome. A truncated candidate set is
 * worse than none, because a reader cannot tell a path that is genuinely absent from one the
 * expansion stopped short of.
 */
public final class ArgMappingCandidates {

    private ArgMappingCandidates() {}

    /** Clears and re-derives the graph's candidate partition; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        // Deepest first, so a row is never deleted while a child still references it.
        Integer deepest = dsl.select(org.jooq.impl.DSL.max(c.DEPTH)).from(c)
            .where(c.GRAPH_NAME.eq(graphName)).fetchOne(0, Integer.class);
        for (int depth = deepest == null ? -1 : deepest; depth >= 0; depth--) {
            dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graphName)).and(c.DEPTH.eq(depth)).execute();
        }
        seed(dsl, graphName);
        int bound = 1 + dsl.fetchCount(GRAPHQL_TYPE,
            GRAPHQL_TYPE.GRAPH_NAME.eq(graphName).and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")));
        for (int depth = 0; expand(dsl, graphName, depth) > 0; depth++) {
            if (depth > bound) {
                throw new IllegalStateException(
                    "graphitron_argmapping_candidate expansion for graph '" + graphName
                        + "' did not converge in " + bound + " passes. Cyclic input nesting is not"
                        + " supported yet and the classifier is expected to have refused this"
                        + " schema before capture wrote it");
            }
        }
    }

    /**
     * Depth-0 rows: one candidate per argument, whatever its type. The path below the argument is
     * empty at a root and the element name is the argument's own, so a reader needs neither a
     * separate root relation nor a string operation to recognise one.
     */
    private static void seed(DSLContext dsl, String graphName) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        dsl.insertInto(c, c.GRAPH_NAME, c.TYPE_NAME, c.FIELD_NAME, c.ARGUMENT_NAME,
                c.PATH, c.PARENT_PATH, c.ELEMENT_NAME, c.NAMED_TYPE, c.DEPTH, c.CLOSES_CYCLE)
            .select(dsl.select(GRAPHQL_ARGUMENT.GRAPH_NAME, GRAPHQL_ARGUMENT.TYPE_NAME,
                    GRAPHQL_ARGUMENT.FIELD_NAME, GRAPHQL_ARGUMENT.ARGUMENT_NAME,
                    val(""), inline((String) null),
                    GRAPHQL_ARGUMENT.ARGUMENT_NAME, GRAPHQL_ARGUMENT.NAMED_TYPE, val(0),
                    val(false))
                .from(GRAPHQL_ARGUMENT)
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName)))
            .execute();
    }

    /**
     * One descent pass: every field of each depth-{@code d} candidate whose type is an input
     * object becomes a depth-{@code d+1} candidate. The child's key is its parent's path with the
     * field name appended, which at a root is the field name alone because the root's path is
     * empty; the key is constructed here and nowhere parsed.
     */
    private static int expand(DSLContext dsl, String graphName, int depth) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;

        // The ancestry this shape does not store, recovered one parent link per level. It is built
        // before the projection because what it decides is a column of the new row: whether the
        // child's own type already stands above it, which is what makes the child the element that
        // closes a cycle. The guard that stops the descent is that column read back on the next
        // pass, so the chain records a fact here instead of silently filtering.
        var ancestors = new ArrayList<GraphitronArgmappingCandidate>();
        for (int level = 0; level < depth; level++) {
            ancestors.add(GRAPHITRON_ARGMAPPING_CANDIDATE.as("ancestor_" + level));
        }
        Condition closesCycle = GRAPHQL_FIELD.NAMED_TYPE.eq(c.NAMED_TYPE);
        for (var ancestor : ancestors) {
            closesCycle = closesCycle.or(GRAPHQL_FIELD.NAMED_TYPE.eq(ancestor.NAMED_TYPE));
        }

        SelectJoinStep<Record10<String, String, String, String, String, String, String,
            String, Integer, Boolean>> from = dsl.select(
                c.GRAPH_NAME, c.TYPE_NAME, c.FIELD_NAME, c.ARGUMENT_NAME,
                when(c.DEPTH.eq(0), GRAPHQL_FIELD.FIELD_NAME)
                    .otherwise(concat(c.PATH, val("."), GRAPHQL_FIELD.FIELD_NAME)),
                c.PATH, GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.NAMED_TYPE, val(depth + 1),
                field(closesCycle))
            .from(c)
            .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(c.GRAPH_NAME)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(c.NAMED_TYPE))
                .and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")))
            .join(GRAPHQL_FIELD).on(GRAPHQL_FIELD.GRAPH_NAME.eq(c.GRAPH_NAME)
                .and(GRAPHQL_FIELD.TYPE_NAME.eq(c.NAMED_TYPE)));

        var previous = c;
        for (var ancestor : ancestors) {
            from = from.join(ancestor).on(ancestor.GRAPH_NAME.eq(previous.GRAPH_NAME)
                .and(ancestor.TYPE_NAME.eq(previous.TYPE_NAME))
                .and(ancestor.FIELD_NAME.eq(previous.FIELD_NAME))
                .and(ancestor.ARGUMENT_NAME.eq(previous.ARGUMENT_NAME))
                .and(ancestor.PATH.eq(previous.PARENT_PATH)));
            previous = ancestor;
        }

        return dsl.insertInto(c, c.GRAPH_NAME, c.TYPE_NAME, c.FIELD_NAME, c.ARGUMENT_NAME,
                c.PATH, c.PARENT_PATH, c.ELEMENT_NAME, c.NAMED_TYPE, c.DEPTH, c.CLOSES_CYCLE)
            .select(from.where(c.GRAPH_NAME.eq(graphName)).and(c.DEPTH.eq(depth))
                .and(c.CLOSES_CYCLE.isFalse()))
            .execute();
    }
}
