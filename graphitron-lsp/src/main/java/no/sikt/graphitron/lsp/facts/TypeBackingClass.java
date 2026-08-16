package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Optional;

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
 * answer here: a surface asking what a class offers has nothing to say in either case, and a
 * caller wanting them apart reads {@code intent_type_backing_conflict}, whose arity is what a
 * rejection would stand on.
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
     * <p>The second read runs only for a type nothing grounded, which is every type whose backing
     * is a hop's or a {@code @table} binding's; a grounded type is answered by the first.
     */
    public static Optional<String> of(StoreHandle store, String typeName) {
        var grounded = store.dsl()
            .selectDistinct(INTENT_TYPE_BACKING_SEED.CLASS_NAME)
            .from(INTENT_TYPE_BACKING_SEED)
            .where(INTENT_TYPE_BACKING_SEED.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_TYPE_BACKING_SEED.TYPE_NAME.eq(typeName))
            .fetch(INTENT_TYPE_BACKING_SEED.CLASS_NAME);
        var candidates = grounded.isEmpty()
            ? store.dsl()
                .selectDistinct(INTENT_TYPE_BACKING.CLASS_NAME)
                .from(INTENT_TYPE_BACKING)
                .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
                .and(INTENT_TYPE_BACKING.TYPE_NAME.eq(typeName))
                .fetch(INTENT_TYPE_BACKING.CLASS_NAME)
            : grounded;
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }
}
