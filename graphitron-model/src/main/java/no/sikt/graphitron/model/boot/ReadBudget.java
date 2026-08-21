package no.sikt.graphitron.model.boot;

import java.util.Optional;

/**
 * How long one statement issued by a {@link StoreReader} may spend before the database aborts it.
 *
 * <p>Two arms rather than a millisecond count, because "no budget" is a thing callers legitimately
 * mean and H2 spells it {@code 0}. A bare number would make every fixture that opens a store pick
 * either that magic value or a figure large enough to be safe, and the second of those is a
 * wall-clock threshold smuggled into a test tier that refuses to fail for slowness. {@link Unbounded}
 * says it structurally instead, so the tier's guarantee holds by construction rather than by whoever
 * chose the number.
 *
 * <p>The budget bounds a <em>statement</em>, not a request. A read transaction may issue several, so
 * where a request's cost matters the bound is this budget times the statement count the
 * {@code *StatementCountTest} tier pins; neither enforcer is sufficient alone. That is also why
 * nothing here belongs in that tier: it counts statements and asserts no duration.
 *
 * <p>The session command is rendered here so no caller spells {@code SET QUERY_TIMEOUT} itself.
 * {@link StoreReader} issues it once at mint, beside the isolation level, for the reason that
 * statement is set there: a property the reader holds for its whole life is the session's rather
 * than each caller's.
 */
public sealed interface ReadBudget {

    /**
     * A statement may run for {@code millis} before the database aborts it.
     *
     * <p>Non-positive values are refused rather than passed through. H2 reads {@code 0} as no limit,
     * so a caller that meant unbounded would get it by accident from a computed zero and never learn
     * that its budget had evaporated; the caller that means unbounded has {@link Unbounded} to say so.
     */
    record Bounded(long millis) implements ReadBudget {
        public Bounded {
            if (millis <= 0) {
                throw new IllegalArgumentException(
                    "a bounded read budget is a positive number of milliseconds, not " + millis
                        + "; a caller that means no limit at all wants ReadBudget.Unbounded");
            }
        }
    }

    /**
     * A statement runs until it finishes, however long that takes. What every store fixture wants,
     * and what the writer-side paths keep: a build's capture legitimately takes seconds.
     */
    record Unbounded() implements ReadBudget {}

    /**
     * The session command that installs this budget, empty where there is nothing to install.
     * Rendered in one place so a second spelling cannot drift from the first.
     */
    default Optional<String> sessionCommand() {
        return switch (this) {
            case Bounded bounded -> Optional.of("SET QUERY_TIMEOUT " + bounded.millis());
            case Unbounded ignored -> Optional.empty();
        };
    }

    /** How this budget reads in a warning or an error response naming what a statement overran. */
    default String describe() {
        return switch (this) {
            case Bounded bounded -> bounded.millis() + " ms";
            case Unbounded ignored -> "no budget";
        };
    }
}
