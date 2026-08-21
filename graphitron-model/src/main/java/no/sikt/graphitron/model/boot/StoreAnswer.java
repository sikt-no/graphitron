package no.sikt.graphitron.model.boot;

/**
 * What a {@link StoreReader#read} produced: either the answer, or the news that the statement ran
 * out of its {@link ReadBudget} before it could produce one.
 *
 * <p>An arm rather than an absence, and the distinction is the point. Every read path already has
 * shapes for "nothing to say": no graph has captured this file, the census holds no such class, the
 * type declares no fields. A read that did not <em>finish</em> is a fourth outcome whose right
 * response is not the same as any of those, because the consumer holds something better than an
 * empty answer, namely whatever it was already showing. Folding the two would make a timeout
 * indistinguishable from a fact's absence, which is exactly the confusion
 * {@link no.sikt.graphitron.model.read.SourceGraph.Uncaptured} exists one level up to prevent.
 *
 * <p>Sealed, so the compiler is the enforcer. A surface added later cannot inherit a posture by
 * saying nothing; it either states one in a switch or does not compile.
 *
 * <p>Deliberately carries no {@code orElse}. A convenience unwrap would let a caller take the
 * absence-shaped path without stating that it had chosen to, which is the whole failure this type
 * exists to make impossible.
 *
 * @param <T> what the read would have produced
 */
public sealed interface StoreAnswer<T> {

    /** The read finished, and {@code value} is what it produced, absences and emptiness included. */
    record Answered<T>(T value) implements StoreAnswer<T> {}

    /**
     * The statement was aborted for overrunning {@code budget}, so there is no answer at all.
     *
     * @param sql the statement the database killed, which is what makes the resulting warning
     *     diagnostic rather than merely present: it is the one thing a bug report needs and the one
     *     thing nobody can reconstruct after the fact
     * @param budget the budget it overran, so a warning can name the number without the surface
     *     that renders it having to know which reader answered
     */
    record OutOfBudget<T>(String sql, ReadBudget budget) implements StoreAnswer<T> {}
}
