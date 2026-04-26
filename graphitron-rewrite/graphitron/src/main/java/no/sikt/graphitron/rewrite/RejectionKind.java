package no.sikt.graphitron.rewrite;

/**
 * Machine-readable category for classifier rejections and validator errors. Carried by
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} and
 * {@link ValidationError}; surfaces in build-time logs as a kebab-case prefix on the message.
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
 *   <li>{@link #INTERNAL_INVARIANT} if a classifier-level contract was violated in a way that
 *       should not be reachable from any valid user schema. Surfaces as a compiler-bug-style
 *       message; treat as a generator bug, not a user-facing error.</li>
 * </ul>
 *
 * <p>When in doubt between {@link #AUTHOR_ERROR} and {@link #INVALID_SCHEMA}, prefer
 * {@code AUTHOR_ERROR}.
 */
public enum RejectionKind {
    INVALID_SCHEMA,
    AUTHOR_ERROR,
    DEFERRED,
    INTERNAL_INVARIANT;

    /**
     * Kebab-case form for the {@code [<kind>] <message>} log prefix emitted by
     * {@code GraphitronSchemaValidator} and consumers of validator output.
     */
    public String displayName() {
        return name().toLowerCase().replace('_', '-');
    }
}
