package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.util.ArrayList;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import no.sikt.graphitron.model.tables.GraphitronArgmappingCandidate;
import org.jooq.Condition;
import org.jooq.Field;

import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_argmapping_candidate}: everything an argMapping
 * right-hand side may name under a field, as a tree. Runs inside capture's own transaction after
 * the flush, clears the run's graph partition first, and re-derives. Stored rather than stated as
 * a view because the descent is recursive and a view has no safe recursive form over it, which is
 * the reason the input occurrence surface is stored too.
 *
 * <p>One relation over two kinds of coordinate. A path written in a directive on a field is written
 * at that field, whose arguments its head may name; a path written on an input field is written at
 * the type declaring it, whose fields its head may name. Both are containers whose members are
 * writable, so both key the same way and neither needs a relation of its own. Splitting them was
 * tried first and the supertype gate refused it, correctly: its roster records what this schema owes
 * rather than what it has decided well, and a new argument-site-versus-field-site twin is the defect
 * this line of work exists to remove.
 *
 * <p>Every row is a right-hand side an author can write, which is why no path is empty: a root is a
 * row with no parent, not a row with a blank path, and the argument or field it names is the row
 * itself rather than an anchor standing beside one.
 *
 * <p>Keying an input field by its declaring type rather than by the occurrences it appears at is
 * what keeps a resolution from fanning out. What follows an input field is fixed by that field's
 * own type, so every occurrence offers the same candidates; on the measured capture one input field
 * occurs at a hundred and twenty-seven distinct paths that would all have produced the same
 * subtree.
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
        // One statement: the parent link cascades, so a root taken out takes its subtree with it.
        dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graphName)).execute();
        int bound = 1 + dsl.fetchCount(GRAPHQL_TYPE,
            GRAPHQL_TYPE.GRAPH_NAME.eq(graphName).and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")));
        seedArgumentRoots(dsl, graphName);
        seedInputFieldRoots(dsl, graphName);
        seedSigilRoots(dsl, graphName);
        for (int depth = 0; expand(dsl, graphName, depth) > 0; depth++) {
            if (depth > bound) {
                throw new IllegalStateException(
                    "graphitron_argmapping_candidate expansion for graph '" + graphName
                        + "' did not converge in " + bound + " passes; the cycle marker has stopped"
                        + " bounding the descent");
            }
        }
    }

    /**
     * The coordinate an argument-rooted path is written at: the field, whose arguments are what a
     * head may name. The argument itself is the path's head and so a row rather than part of this.
     */
    private static Field<String> argumentCoordinate() {
        return concat(GRAPHQL_ARGUMENT.TYPE_NAME, val("."), GRAPHQL_ARGUMENT.FIELD_NAME);
    }

    /** The coordinate an input-field-relative path is written at: the input type declaring it. */
    private static Field<String> inputTypeCoordinate() {
        return GRAPHQL_FIELD.TYPE_NAME;
    }

    /**
     * One root per argument, whatever its type. The argument is itself writable, so its row is the
     * path that names it rather than an anchor beside one; a root is told apart by having no parent,
     * which is a column a reader already has, and never by an empty path.
     */
    private static void seedArgumentRoots(DSLContext dsl, String graphName) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        dsl.insertInto(c, c.GRAPH_NAME, c.COORDINATE, c.PATH, c.PARENT_PATH,
                c.ELEMENT_KIND, c.TYPE_NAME, c.FIELD_NAME, c.ELEMENT_NAME,
                c.CONTAINER_TYPE_NAME, c.NAMED_TYPE, c.IS_LIST, c.DEPTH, c.CLOSES_CYCLE)
            .select(dsl.select(GRAPHQL_ARGUMENT.GRAPH_NAME, argumentCoordinate(),
                    GRAPHQL_ARGUMENT.ARGUMENT_NAME, inline((String) null), val("ARGUMENT"),
                    GRAPHQL_ARGUMENT.TYPE_NAME, GRAPHQL_ARGUMENT.FIELD_NAME,
                    GRAPHQL_ARGUMENT.ARGUMENT_NAME, inline((String) null),
                    GRAPHQL_ARGUMENT.NAMED_TYPE, GRAPHQL_ARGUMENT.IS_LIST, val(0), val(false))
                .from(GRAPHQL_ARGUMENT)
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName)))
            .execute();
    }

    /**
     * One root per sigil an entry names. A sigil is one more thing an argMapping right-hand side
     * may name, so it is a candidate like any other and an entry naming one matches it the same
     * way; what it is not is a position on the input surface, which is why it has no named type
     * and why nothing descends from it.
     *
     * <p>Seeded from the entries rather than from a vocabulary, so which sites admit a sigil stays
     * one rule in {@code ArgMappingSigil} and is never restated here. An entry at a site that does
     * not admit one is rejected at parse and never reaches this relation, so a candidate exists
     * exactly where a sigil was both admitted and written. DISTINCT because one site may bind the
     * same sigil to several parameters, which is several entries and one candidate.
     */
    private static void seedSigilRoots(DSLContext dsl, String graphName) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        var e = GRAPHITRON_ARGMAPPING_ENTRY;
        dsl.insertInto(c, c.GRAPH_NAME, c.COORDINATE, c.PATH, c.PARENT_PATH,
                c.ELEMENT_KIND, c.TYPE_NAME, c.FIELD_NAME, c.ELEMENT_NAME,
                c.CONTAINER_TYPE_NAME, c.NAMED_TYPE, c.IS_LIST, c.DEPTH, c.CLOSES_CYCLE)
            .select(dsl.selectDistinct(e.GRAPH_NAME, e.CANDIDATE_COORDINATE, e.CANDIDATE_PATH,
                    inline((String) null), val("SIGIL"), e.TYPE_NAME, e.FIELD_NAME,
                    e.HEAD_SEGMENT, inline((String) null),
                    inline((String) null), val(false), val(0), val(false))
                .from(e)
                .where(e.GRAPH_NAME.eq(graphName).and(e.HEAD_KIND.eq("SIGIL"))))
            .execute();
    }

    /**
     * One root per field of an input object type, under the type that declares it. A path written
     * on such a field heads at the field's own name, so the field is the root and the coordinate is
     * its container, which is the same shape an argument takes under its field.
     */
    private static void seedInputFieldRoots(DSLContext dsl, String graphName) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        dsl.insertInto(c, c.GRAPH_NAME, c.COORDINATE, c.PATH, c.PARENT_PATH,
                c.ELEMENT_KIND, c.TYPE_NAME, c.FIELD_NAME, c.ELEMENT_NAME,
                c.CONTAINER_TYPE_NAME, c.NAMED_TYPE, c.IS_LIST, c.DEPTH, c.CLOSES_CYCLE)
            .select(dsl.select(GRAPHQL_FIELD.GRAPH_NAME, inputTypeCoordinate(),
                    GRAPHQL_FIELD.FIELD_NAME, inline((String) null), val("INPUT_FIELD"),
                    GRAPHQL_FIELD.TYPE_NAME, inline((String) null),
                    GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.NAMED_TYPE,
                    GRAPHQL_FIELD.IS_LIST, val(0), val(false))
                .from(GRAPHQL_FIELD)
                .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME)
                    .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME))
                    .and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")))
                .where(GRAPHQL_FIELD.GRAPH_NAME.eq(graphName)))
            .execute();
    }

    /**
     * One descent pass, over both kinds of coordinate at once: every field of each depth-{@code d}
     * candidate whose type is an input object becomes a depth-{@code d+1} candidate at the same
     * coordinate. The child's key is its parent's path with the field name appended, uniformly and
     * including at a root, the root carrying its own head; the key is constructed here and nowhere
     * parsed.
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

        var from = dsl.select(
                c.GRAPH_NAME, c.COORDINATE,
                concat(c.PATH, val("."), GRAPHQL_FIELD.FIELD_NAME),
                c.PATH, val("INPUT_FIELD"), c.TYPE_NAME, c.FIELD_NAME,
                GRAPHQL_FIELD.FIELD_NAME, c.NAMED_TYPE, GRAPHQL_FIELD.NAMED_TYPE,
                GRAPHQL_FIELD.IS_LIST, val(depth + 1), field(closesCycle))
            .from(c)
            .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(c.GRAPH_NAME)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(c.NAMED_TYPE))
                .and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")))
            .join(GRAPHQL_FIELD).on(GRAPHQL_FIELD.GRAPH_NAME.eq(c.GRAPH_NAME)
                .and(GRAPHQL_FIELD.TYPE_NAME.eq(c.NAMED_TYPE)));

        var previous = c;
        for (var ancestor : ancestors) {
            from = from.join(ancestor).on(ancestor.GRAPH_NAME.eq(previous.GRAPH_NAME)
                .and(ancestor.COORDINATE.eq(previous.COORDINATE))
                .and(ancestor.PATH.eq(previous.PARENT_PATH)));
            previous = ancestor;
        }

        return dsl.insertInto(c, c.GRAPH_NAME, c.COORDINATE, c.PATH, c.PARENT_PATH,
                c.ELEMENT_KIND, c.TYPE_NAME, c.FIELD_NAME, c.ELEMENT_NAME,
                c.CONTAINER_TYPE_NAME, c.NAMED_TYPE, c.IS_LIST, c.DEPTH, c.CLOSES_CYCLE)
            .select(from.where(c.GRAPH_NAME.eq(graphName)).and(c.DEPTH.eq(depth))
                .and(c.CLOSES_CYCLE.isFalse()))
            .execute();
    }
}
