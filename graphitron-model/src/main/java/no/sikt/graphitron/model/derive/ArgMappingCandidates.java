package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.util.ArrayList;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import no.sikt.graphitron.model.tables.GraphitronArgmappingCandidate;
import no.sikt.graphitron.model.tables.GraphqlArgument;
import no.sikt.graphitron.model.tables.GraphqlArgumentElement;
import no.sikt.graphitron.model.tables.GraphqlElement;
import no.sikt.graphitron.model.tables.GraphqlField;
import no.sikt.graphitron.model.tables.GraphqlFieldElement;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of {@code graphitron_argmapping_candidate}: every right-hand side an
 * argMapping may write, under the schema coordinate the directive carrying it sits on. Runs inside
 * capture's own transaction after the flush, clears the run's graph partition first, and
 * re-derives. Stored rather than stated as a view because the descent is recursive and a view has
 * no safe recursive form over it, which is the reason the input occurrence surface is stored too.
 *
 * <p>One relation over three coordinates and no discriminator between them, because the coordinate
 * itself says which is which. A directive on a field is written at that field, whose arguments its
 * head may name. A directive on an argument or on an input field is written at that argument or
 * field, and what it may name is the thing itself and whatever the thing opens into.
 *
 * <p>Two spellings for the same position, and both are candidates. An argument-level or
 * input-field-level argMapping is written today with the coordinate's own name repeated as the
 * head, and the resolution used to enforce exactly that by comparing a stored head against the
 * argument or field name. Both spellings are here instead: the clean one and the repeating one,
 * the second marked deprecated, so a warning can prefer one while neither stops resolving and no
 * reader spells a scope test of its own. The repeating spellings are not a second tree; they are
 * the descent below the coordinate's own name, which the one expansion produces from the one rule.
 *
 * <p>Precedence where two readings meet on one spelling is the coordinate's own name first, which
 * is what the enforced spelling meant before this relation held both. It can only be contested at
 * depth zero, and only by an input field of the same name as the argument or field carrying it: no
 * clean path can then begin with that name, so nothing below can collide either. The loser is not
 * a row, so the winner carries a mark rather than leaving an absence to be inferred.
 *
 * <p>Every row is a right-hand side an author can write, which is why no path is empty: a root is a
 * row with no parent, not a row with a blank path, and the thing it names is the row itself rather
 * than an anchor standing beside one.
 *
 * <p>Seeded from every argument rather than only the input-object-typed ones. A bare name with no
 * dots is a legal right-hand side and is what an entry with no {@code argMapping} binds to
 * implicitly, so a scalar argument is a candidate and needs a root row; a relation holding only the
 * arguments that descend covers a different population, and that mismatch is why the argMapping
 * rules walked positions instead of joining.
 *
 * <p>Expansion is depth-stratified and stops at the first revisit of a type already on the
 * candidate's own ancestry. Cyclic input nesting is legal GraphQL and does reach capture, which
 * runs before anything classifies: the classifier refuses the field that closes a cycle, but the
 * types are captured either way. The closing element is nameable, so it gets a row and is marked as
 * what it is, and nothing below it is written. Marking it rather than merely stopping is the point:
 * a candidate with no children is otherwise ambiguous between a leaf and a stopping point, and a
 * relation whose absences carry meaning is the shape this whole line of work exists to remove.
 *
 * <p>The ancestry test is a join chain rather than a lookup in a closure relation, which is the one
 * price this shape pays. A candidate carries its parent and not its ancestors, so pass {@code d}
 * walks {@code d} parent links to reach the roots; that is bounded by the same pass bound as the
 * expansion, and it buys a single relation where the neighbour needs two. The pass bound itself is
 * an invariant check: with the guard in place the expansion is monotone, so if it ever fires the
 * guard has stopped working and failing is the only correct outcome. A truncated candidate set is
 * worse than none, because a reader cannot tell a path that is genuinely absent from one the
 * expansion stopped short of. The bound allows one level beyond the input nesting depth, the
 * repeating spelling of a path being one segment longer than the clean one.
 */
public final class ArgMappingCandidates {

    private ArgMappingCandidates() {}

    /** Clears and re-derives the graph's candidate partition; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        // One statement: the parent link cascades, so a root taken out takes its subtree with it.
        dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graphName)).execute();
        int bound = 2 + dsl.fetchCount(GRAPHQL_TYPE,
            GRAPHQL_TYPE.GRAPH_NAME.eq(graphName).and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")));
        seedFieldCoordinateArguments(dsl, graphName);
        seedCarrierItself(dsl, graphName);
        seedCarrierChildren(dsl, graphName);
        markContestedCarrierName(dsl, graphName);
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

    /** The column list every seeding statement writes, in one place so the arms cannot drift. */
    private static Field<?>[] columns() {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        return new Field<?>[] {c.GRAPH_NAME, c.COORDINATE, c.PATH, c.PARENT_PATH, c.NAME,
            c.ELEMENT_KIND, c.CONTAINER_TYPE_NAME, c.NAMED_TYPE, c.IS_LIST, c.DEPTH,
            c.CLOSES_CYCLE, c.DEPRECATED, c.AMBIGUOUS};
    }

    /**
     * What each coordinate that carries its own value is called and what type that value has: an
     * argument at an argument coordinate, an input field at an input-field coordinate. A field
     * coordinate is not here, its members being the field's arguments rather than one value of its
     * own, and that absence is what keeps a field coordinate out of every rule stated over this.
     *
     * <p>Reached from the coordinate's {@code graphql_element} row, whose kind capture already
     * decided, by a flat chain of left joins on keys: the argument arm and the input-field arm side
     * by side, the four columns a coalesce over the two. At most one arm matches, the element's key
     * admitting one kind per coordinate. Flat rather than a union in a derived table because H2
     * seeks into neither a union nor a derived table, and a reader arriving with one coordinate
     * then re-runs the whole of it once per row.
     */
    private record Carrier(GraphqlElement element, GraphqlArgumentElement argumentElement,
                           GraphqlArgument argument, GraphqlFieldElement fieldElement,
                           GraphqlField inputField) {

        /** The chain hanging off {@code element}, under aliases no reader of this class uses. */
        static Carrier of(GraphqlElement element) {
            return new Carrier(element, GRAPHQL_ARGUMENT_ELEMENT.as("carrier_argument_element"),
                GRAPHQL_ARGUMENT.as("carrier_argument"),
                GRAPHQL_FIELD_ELEMENT.as("carrier_field_element"),
                GRAPHQL_FIELD.as("carrier_field"));
        }

        /** The element kinds that carry a value of their own. */
        static Condition carries(GraphqlElement element) {
            return element.ELEMENT_KIND.in(inline("FIELD_ARGUMENT"), inline("INPUT_FIELD"));
        }

        /** Appends the two arms to a join tree that already holds {@link #element}. */
        <R extends Record> SelectJoinStep<R> appendTo(SelectJoinStep<R> tree) {
            var e = element;
            var ae = argumentElement;
            var a = argument;
            var fe = fieldElement;
            var f = inputField;
            return tree
                .leftJoin(ae).on(ae.GRAPH_NAME.eq(e.GRAPH_NAME).and(ae.COORDINATE.eq(e.COORDINATE)))
                .leftJoin(a).on(a.GRAPH_NAME.eq(ae.GRAPH_NAME).and(a.TYPE_NAME.eq(ae.TYPE_NAME))
                    .and(a.FIELD_NAME.eq(ae.FIELD_NAME)).and(a.ARGUMENT_NAME.eq(ae.ARGUMENT_NAME)))
                .leftJoin(fe).on(fe.GRAPH_NAME.eq(e.GRAPH_NAME).and(fe.COORDINATE.eq(e.COORDINATE)))
                .leftJoin(f).on(f.GRAPH_NAME.eq(fe.GRAPH_NAME).and(f.TYPE_NAME.eq(fe.TYPE_NAME))
                    .and(f.FIELD_NAME.eq(fe.FIELD_NAME)));
        }

        Field<String> name() { return coalesce(argument.ARGUMENT_NAME, inputField.FIELD_NAME); }
        Field<String> type() { return coalesce(argument.NAMED_TYPE, inputField.NAMED_TYPE); }
        Field<Boolean> isList() { return coalesce(argument.IS_LIST, inputField.IS_LIST); }

        /** The input type declaring the carried field; null for an argument, which no type declares. */
        Field<String> declaringType() { return inputField.TYPE_NAME; }
    }

    /**
     * One root per argument at the coordinate of the field declaring it: what a directive on that
     * field may name. The argument is itself writable, so its row is the path that names it rather
     * than an anchor beside one, and a root is told apart by having no parent. Nothing here repeats
     * a name, a head at a field coordinate naming an argument and not the field.
     */
    private static void seedFieldCoordinateArguments(DSLContext dsl, String graphName) {
        var a = GRAPHQL_ARGUMENT;
        var fc = GRAPHQL_FIELD_ELEMENT;
        dsl.insertInto(GRAPHITRON_ARGMAPPING_CANDIDATE, columns())
            .select(dsl.select(a.GRAPH_NAME, fc.COORDINATE, a.ARGUMENT_NAME,
                    inline((String) null), a.ARGUMENT_NAME, val("ARGUMENT"),
                    inline((String) null), a.NAMED_TYPE, a.IS_LIST, val(0),
                    val(false), val(false), val(false))
                .from(a)
                .join(fc).on(fc.GRAPH_NAME.eq(a.GRAPH_NAME)
                    .and(fc.TYPE_NAME.eq(a.TYPE_NAME)).and(fc.FIELD_NAME.eq(a.FIELD_NAME)))
                .where(a.GRAPH_NAME.eq(graphName)))
            .execute();
    }

    /**
     * The coordinate's own name where the coordinate carries a value: the one spelling that binds
     * the whole of it. Not deprecated, there being no other way to say it, and the root every
     * repeating spelling below hangs from.
     */
    private static void seedCarrierItself(DSLContext dsl, String graphName) {
        var e = GRAPHQL_ELEMENT;
        var k = Carrier.of(e);
        dsl.insertInto(GRAPHITRON_ARGMAPPING_CANDIDATE, columns())
            .select(k.appendTo(dsl.select(e.GRAPH_NAME, e.COORDINATE, k.name(),
                    inline((String) null), k.name(),
                    // An argument coordinate carries an argument and declares no type; an
                    // input-field coordinate carries a field the input type above it declares.
                    // One column answers both, so the two arms of the carrier need no
                    // discriminator of their own.
                    when(k.declaringType().isNull(), val("ARGUMENT")).otherwise(val("INPUT_FIELD")),
                    k.declaringType(), k.type(), k.isList(), val(0),
                    val(false), val(false), val(false))
                .from(e))
                .where(e.GRAPH_NAME.eq(graphName)).and(Carrier.carries(e))
                .and(k.name().isNotNull()))
            .execute();
    }

    /**
     * The clean roots: the fields of the carrier's own type, named without repeating the carrier.
     * Excludes a field whose name is the carrier's own, which the row above already claimed; that
     * is the one place two readings can meet at a root, and the mark for it is written separately
     * so the loser's absence is not silent.
     */
    private static void seedCarrierChildren(DSLContext dsl, String graphName) {
        var e = GRAPHQL_ELEMENT;
        var k = Carrier.of(e);
        var x = GRAPHQL_FIELD.as("child");
        dsl.insertInto(GRAPHITRON_ARGMAPPING_CANDIDATE, columns())
            .select(k.appendTo(dsl.select(e.GRAPH_NAME, e.COORDINATE, x.FIELD_NAME,
                    inline((String) null), x.FIELD_NAME, val("INPUT_FIELD"),
                    k.type(), x.NAMED_TYPE, x.IS_LIST, val(0),
                    field(x.NAMED_TYPE.eq(k.type())), val(false), val(false))
                .from(e))
                .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(e.GRAPH_NAME)
                    .and(GRAPHQL_TYPE.TYPE_NAME.eq(k.type()))
                    .and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")))
                .join(x).on(x.GRAPH_NAME.eq(e.GRAPH_NAME).and(x.TYPE_NAME.eq(k.type())))
                .where(e.GRAPH_NAME.eq(graphName)).and(Carrier.carries(e))
                .and(x.FIELD_NAME.ne(k.name())))
            .execute();
    }

    /**
     * Marks the carrier's own name where a field of the carrier's type would have claimed the same
     * spelling. The carrier wins, which is what the enforced spelling meant before this relation
     * held both readings; the mark is what keeps the other from being an absence a reader has to
     * infer. Which reading lost needs no column: it is the carrier's type opened one step.
     */
    private static void markContestedCarrierName(DSLContext dsl, String graphName) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        var e = GRAPHQL_ELEMENT;
        var k = Carrier.of(e);
        var x = GRAPHQL_FIELD.as("child");
        dsl.update(c).set(c.AMBIGUOUS, true)
            .where(c.GRAPH_NAME.eq(graphName)).and(c.DEPTH.eq(0))
            .and(exists(k.appendTo(dsl.selectOne().from(e))
                .join(x).on(x.GRAPH_NAME.eq(e.GRAPH_NAME).and(x.TYPE_NAME.eq(k.type()))
                    .and(x.FIELD_NAME.eq(k.name())))
                .where(e.GRAPH_NAME.eq(c.GRAPH_NAME))
                .and(e.COORDINATE.eq(c.COORDINATE))
                .and(Carrier.carries(e))
                .and(k.name().eq(c.PATH))))
            .execute();
    }

    /**
     * One root per sigil an entry names. A sigil is one more thing an argMapping right-hand side
     * may name, so it is a candidate like any other and an entry naming one matches it the same
     * way; what it is not is a position on the input surface, which is why it has no named type and
     * why nothing descends from it.
     *
     * <p>Seeded from the entries rather than from a vocabulary, so which sites admit a sigil stays
     * one rule in {@code ArgMappingSigil} and is never restated here. An entry at a site that does
     * not admit one is rejected at parse and never reaches this relation, so a candidate exists
     * exactly where a sigil was both admitted and written. DISTINCT because one site may bind the
     * same sigil to several parameters, which is several entries and one candidate. A sigil begins
     * with a character no GraphQL name may begin with, so it collides with nothing here.
     */
    private static void seedSigilRoots(DSLContext dsl, String graphName) {
        var e = GRAPHITRON_ARGMAPPING_ENTRY;
        dsl.insertInto(GRAPHITRON_ARGMAPPING_CANDIDATE, columns())
            .select(dsl.selectDistinct(e.GRAPH_NAME, e.COORDINATE, e.WRITTEN_PATH,
                    inline((String) null), e.TAIL_NAME, val("SIGIL"),
                    inline((String) null), inline((String) null), val(false), val(0),
                    val(false), val(false), val(false))
                .from(e)
                .where(e.GRAPH_NAME.eq(graphName).and(e.WRITTEN_PATH.startsWith("$"))))
            .execute();
    }

    /**
     * One descent pass, over every coordinate at once: every field of each depth-{@code d}
     * candidate whose type is an input object becomes a depth-{@code d+1} candidate at the same
     * coordinate. The child's key is its parent's path with the field name appended, uniformly and
     * including at a root; the key is constructed here and nowhere parsed.
     *
     * <p>This is also where the repeating spelling comes from, and where it is marked. The carrier's
     * own row descends like any other, and everything below it repeats the carrier's name by
     * construction, so deprecation is that one fact propagated down the parent link rather than a
     * second tree built by a second rule. Nothing can then disagree about where the descent stops
     * or what closes a cycle, and the two spellings cannot collide: a clean path never begins with
     * the carrier's name, that root having lost the spelling to the carrier itself.
     */
    private static int expand(DSLContext dsl, String graphName, int depth) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        var e = GRAPHQL_ELEMENT;
        var k = Carrier.of(e);

        // The ancestry this shape does not store, recovered one parent link per level. It is built
        // before the projection because what it decides is a column of the new row: whether the
        // child's own type already stands above it, which is what makes the child the element that
        // closes a cycle. The guard that stops the descent is that column read back on the next
        // pass, so the chain records a fact here instead of silently filtering. The carrier's own
        // type is on that ancestry too where the coordinate has one, and it is joined rather than
        // walked because a clean root has no parent row standing for it.
        var ancestors = new ArrayList<GraphitronArgmappingCandidate>();
        for (int level = 0; level < depth; level++) {
            ancestors.add(GRAPHITRON_ARGMAPPING_CANDIDATE.as("ancestor_" + level));
        }
        Condition closesCycle = GRAPHQL_FIELD.NAMED_TYPE.eq(c.NAMED_TYPE)
            .or(GRAPHQL_FIELD.NAMED_TYPE.eq(k.type()));
        for (var ancestor : ancestors) {
            closesCycle = closesCycle.or(GRAPHQL_FIELD.NAMED_TYPE.eq(ancestor.NAMED_TYPE));
        }

        var from = k.appendTo(dsl.select(
                c.GRAPH_NAME, c.COORDINATE,
                concat(c.PATH, val("."), GRAPHQL_FIELD.FIELD_NAME),
                c.PATH, GRAPHQL_FIELD.FIELD_NAME, val("INPUT_FIELD"),
                c.NAMED_TYPE, GRAPHQL_FIELD.NAMED_TYPE, GRAPHQL_FIELD.IS_LIST,
                val(depth + 1), coalesce(field(closesCycle), inline(false)),
                coalesce(field(c.DEPRECATED.isTrue().or(c.PATH.eq(k.name()))), inline(false)),
                val(false))
            .from(c)
            .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(c.GRAPH_NAME)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(c.NAMED_TYPE))
                .and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")))
            .join(GRAPHQL_FIELD).on(GRAPHQL_FIELD.GRAPH_NAME.eq(c.GRAPH_NAME)
                .and(GRAPHQL_FIELD.TYPE_NAME.eq(c.NAMED_TYPE)))
            .leftJoin(e).on(e.GRAPH_NAME.eq(c.GRAPH_NAME).and(e.COORDINATE.eq(c.COORDINATE))
                .and(Carrier.carries(e))));

        var previous = c;
        for (var ancestor : ancestors) {
            from = from.join(ancestor).on(ancestor.GRAPH_NAME.eq(previous.GRAPH_NAME)
                .and(ancestor.COORDINATE.eq(previous.COORDINATE))
                .and(ancestor.PATH.eq(previous.PARENT_PATH)));
            previous = ancestor;
        }

        return dsl.insertInto(c, columns())
            .select(from.where(c.GRAPH_NAME.eq(graphName)).and(c.DEPTH.eq(depth))
                .and(c.CLOSES_CYCLE.isFalse()))
            .execute();
    }
}
