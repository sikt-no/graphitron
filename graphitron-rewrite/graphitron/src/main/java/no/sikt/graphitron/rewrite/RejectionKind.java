package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.Rejection;

/**
 * Machine-readable category for classifier rejections and validator errors. Carried by
 * {@link ValidationError}; surfaces in build-time logs as a kebab-case prefix on the message.
 *
 * <p>The classifier itself produces a {@link Rejection} sealed-variant, not a {@link RejectionKind};
 * this enum is the projection layer the validator and log formatter use to produce the
 * {@code [<kind>] <message>} prefix. {@link #of(Rejection)} is the total projection.
 *
 * <p>Categorisation rule of thumb:
 *
 * <ul>
 *   <li>{@link #AUTHOR_ERROR} if the rejection points at a name the schema author can correct
 *       by typo-fixing, by adding a missing schema element, or by pointing the directive at
 *       something the jOOQ catalog or SDL registry knows about.</li>
 *   <li>{@link #INVALID_SCHEMA} if the rejection is structural — no rename or reference fix
 *       repairs it; the author has to drop or replace a directive. Reserved for "this
 *       combination cannot work, period".</li>
 *   <li>{@link #DEFERRED} if the generator does not yet support the requested shape but plans
 *       to. Tracked on the rewrite roadmap.</li>
 * </ul>
 *
 * <p>When in doubt between {@link #AUTHOR_ERROR} and {@link #INVALID_SCHEMA}, prefer
 * {@code AUTHOR_ERROR}.
 *
 * <p>Generator-bug-style "unreachable branch" rejections do not have an enum value: they
 * throw {@link AssertionError} at the producing site instead of routing through this
 * channel. A user-facing message in the validator log is the wrong shape for "you can't
 * fix this; file a generator bug".
 */
public enum RejectionKind {
    INVALID_SCHEMA,
    AUTHOR_ERROR,
    DEFERRED;

    /**
     * Total projection from a sealed {@link Rejection} variant to its {@link RejectionKind}.
     * Exhaustive at the top-level {@code permits}; sub-arms of {@link Rejection.AuthorError}
     * and {@link Rejection.InvalidSchema} project transparently to the same kind.
     */
    public static RejectionKind of(Rejection rejection) {
        return switch (rejection) {
            case Rejection.AuthorError ignored   -> AUTHOR_ERROR;
            case Rejection.InvalidSchema ignored -> INVALID_SCHEMA;
            case Rejection.Deferred ignored      -> DEFERRED;
        };
    }

    /**
     * Kebab-case form for the {@code [<kind>] <message>} log prefix emitted by
     * {@code GraphitronSchemaValidator} and consumers of validator output.
     */
    public String displayName() {
        return name().toLowerCase().replace('_', '-');
    }
}
