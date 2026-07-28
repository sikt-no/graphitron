package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;

/**
 * A join hop the producer has proven FK-derived: {@link #pairs} is {@link JoinStep.Hop#on()}
 * narrowed once, at production, so renderers read the column pairs directly instead of
 * re-checking the arm. This is a borrow-plus-proof pair, not a copy: the hop itself is the
 * model's, and the second slot carries the narrowing the render-time {@code IllegalStateException}
 * throws used to re-derive per site.
 */
public record FkHop(JoinStep.Hop hop, On.ColumnPairs pairs) {

    public FkHop {
        if (hop == null || pairs == null) {
            throw new IllegalArgumentException("an FK hop pairs a join hop with its proven column pairs");
        }
        if (!pairs.equals(hop.on())) {
            throw new IllegalArgumentException(
                "an FK hop's pairs must be the hop's own ON arm; narrowing a different hop's pairs"
                + " onto this one would let a renderer join on the wrong columns");
        }
    }

    /**
     * The one produce-time narrowing check: accepts exactly a catalog-FK hop
     * ({@link JoinStep.Hop} whose {@code on()} is {@link On.ColumnPairs}) and rejects everything
     * else, replacing the per-renderer throws that used to guard the same fact at emit time.
     */
    public static FkHop narrow(JoinStep step, String context) {
        if (step instanceof JoinStep.Hop hop && hop.on() instanceof On.ColumnPairs cp) {
            return new FkHop(hop, cp);
        }
        throw new IllegalStateException(
            "condition reach hop for " + context + " is not FK-derived (" + step
            + "); the validator must reject unresolved reach paths before production");
    }
}
