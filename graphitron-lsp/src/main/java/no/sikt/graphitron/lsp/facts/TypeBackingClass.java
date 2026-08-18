package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.TableField;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_SEED;

/**
 * Which Java class stands for an SDL type, for a surface that must name one. The rows are
 * {@code intent_type_backing}'s, the coalesced answer over the {@code @table} binding read through
 * its table's record and the closure over producer returns and accessor hops; what a reader makes
 * of more than one of them is this class.
 *
 * <p>The relation prefers no row over another, so both rules below are the reader's and are stated
 * here rather than assumed of the store.
 *
 * <ul>
 *   <li><b>A grounding beats a hop.</b> A class a producer of the type's own delivers is a row of
 *       {@code intent_type_backing_seed}; a class reached by reading the type off another type's
 *       member is not. A hop reads the parent's member type without checking it against the child's
 *       own grounding, so where the two disagree the hop is wrong rather than merely second, and
 *       the answer is drawn from the seeds whenever the type has any.</li>
 *   <li><b>A type still contested has no answer.</b> Two producers naming different classes leave
 *       nothing to prefer between them, and a surface that guessed would offer one class's members
 *       while the generator bound the other. Empty, which every caller already renders as silence.
 *       That is also what the walk this replaces does with the same population, by refusing to bind
 *       at all once its observations disagree.</li>
 * </ul>
 *
 * <p>Empty is likewise what an unbacked type gives, and the two absences are deliberately one
 * answer here: a surface asking what a class offers has nothing to say in either case. A surface
 * that does want them apart, because it is explaining a type rather than resolving one, reads
 * {@code intent_type_backing_conflict} beside these two populations and applies {@link #resolve} to
 * tell which absence it is holding; {@link ClaimFacts#ofType} is that surface's reader.
 *
 * <p>This is the question the language server used to put to the classification walk's projection,
 * which resolved it by reflection per build and carried the answer as a permit's class name.
 */
public final class TypeBackingClass {

    private TypeBackingClass() {}

    /**
     * The class backing {@code typeName} in this graph, empty where the store reaches none or
     * reaches more than one after the grounding rule.
     *
     * <p>Lazy in the second population, which is the grounding rule expressed as work not done: a
     * type a producer of its own grounds needs no reading of what a hop reached, the seeds having
     * already answered. A caller resolving a whole region asks both populations as arms of its own
     * statement and applies {@link #resolve} over what it holds.
     */
    public static Optional<String> of(StoreHandle store, String typeName) {
        var grounded = candidatesOf(store, INTENT_TYPE_BACKING_SEED.TYPE_NAME,
            INTENT_TYPE_BACKING_SEED.CLASS_NAME, INTENT_TYPE_BACKING_SEED.GRAPH_NAME, typeName);
        if (!grounded.isEmpty()) return resolve(grounded, Set.of());
        return resolve(Set.of(), candidatesOf(store, INTENT_TYPE_BACKING.TYPE_NAME,
            INTENT_TYPE_BACKING.CLASS_NAME, INTENT_TYPE_BACKING.GRAPH_NAME, typeName));
    }

    /**
     * Both rules above, over candidates a caller already holds: the seeds where the type has any,
     * else the classes the closure reached, and an answer only where exactly one class stands.
     *
     * <p>Here rather than inlined in the reader above because a caller assembling one statement of
     * its own fetches the same two populations as arms of it, and the rule that decides between them
     * belongs to the question rather than to any one reading of it. Both populations must arrive
     * distinct: more than one element is taken to be disagreement, so a repeated observation would
     * read as a contest.
     */
    public static Optional<String> resolve(Collection<String> grounded, Collection<String> reached) {
        var candidates = grounded.isEmpty() ? reached : grounded;
        return candidates.size() == 1
            ? Optional.of(candidates.iterator().next())
            : Optional.empty();
    }

    private static Set<String> candidatesOf(
        StoreHandle store, TableField<?, String> typeColumn, TableField<?, String> classColumn,
        TableField<?, String> graphColumn, String typeName
    ) {
        return new LinkedHashSet<>(store.dsl()
            .selectDistinct(classColumn)
            .from(typeColumn.getTable())
            .where(graphColumn.eq(store.graphName()))
            .and(typeColumn.eq(typeName))
            .fetch(classColumn));
    }
}
