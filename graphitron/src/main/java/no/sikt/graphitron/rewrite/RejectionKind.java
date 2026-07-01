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
     * Kebab-case form, used in compact aggregations like the watch formatter's per-error tag and
     * its summary line ({@code "3 author-error, 1 deferred"}).
     */
    public String displayName() {
        return name().toLowerCase().replace('_', '-');
    }

    /**
     * Sentence-cased label for the {@code <label>: <message>} prefix in single-line gcc-style
     * validator output ({@code "file:line:col: Author error: <msg>"}). Replaces the older
     * {@code "error: [<kebab>]"} prefix, which doubled the SLF4J/Maven {@code [ERROR]} level and
     * read as a tag rather than a sentence.
     */
    public String messageLabel() {
        return switch (this) {
            case AUTHOR_ERROR   -> "Author error";
            case INVALID_SCHEMA -> "Invalid schema";
            case DEFERRED       -> "Deferred";
        };
    }
}
