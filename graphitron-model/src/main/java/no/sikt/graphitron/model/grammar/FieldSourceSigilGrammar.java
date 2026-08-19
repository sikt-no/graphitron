package no.sikt.graphitron.model.grammar;

/**
 * The sigil literals an author may write in place of a member name in {@code @field(name:)}, and
 * the message that says where one of them is admitted.
 *
 * <p>{@code $source} binds the SDL field to the upstream Java value as a whole rather than to a
 * member of it; {@code $errors} forces the local-context transport on a payload type's errors
 * field. Both are values the author types, so recognising one is the same kind of question as the
 * splits beside it here: what did the written value say.
 *
 * <p>It lives in the module both readers depend on because the literal and the rejection message
 * have to agree across a module boundary. The generator rejects a sigil written at a site that does
 * not admit it, and the language server marks the same site while the author is still typing; a
 * literal spelled twice, or a message worded twice, is the two surfaces disagreeing about the same
 * value. What the generator does with an admitted sigil stays with the generator, which holds the
 * parse into its own sealed result and everything downstream of it.
 */
public final class FieldSourceSigilGrammar {

    /** Binds the SDL field to {@code env.getSource()}, the upstream Java value as a whole. */
    public static final String UPSTREAM_ROOT = "$source";

    /**
     * On an errors-shaped field of a payload-returning mutation type, forces the
     * {@code env.getLocalContext()} transport rather than the accessor-then-local-context fallback.
     */
    public static final String LOCAL_CONTEXT = "$errors";

    private FieldSourceSigilGrammar() {
    }

    /** Whether {@code value} is the upstream-root sigil. Null-safe: an absent value is not one. */
    public static boolean isUpstreamRoot(String value) {
        return UPSTREAM_ROOT.equals(value);
    }

    /**
     * The canonical message for an upstream-root sigil written where it is not admitted. Single
     * source for both the generator's rejection and the language server's squiggle, so an author
     * reading one and then the other is not told two different things.
     */
    public static String notDefinedHereMessage() {
        return "'" + UPSTREAM_ROOT + "' is not defined at this site; "
            + "it is only valid on the data field of a payload type returned by a "
            + "@service-backed mutation.";
    }
}
