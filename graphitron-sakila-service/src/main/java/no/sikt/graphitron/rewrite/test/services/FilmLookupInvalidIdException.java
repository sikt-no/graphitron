package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture for {@code @error} channel generation: the GENERIC-handler exception for the
 * {@code filmLookup} query's {@code FilmLookupInvalid} {@code @error} type. Sibling of
 * {@link FilmLookupNotFoundException}; the two exist together so the union's
 * TypeResolver dispatch ladder has more than one branch and the
 * {@code GraphitronSchemaClassGenerator.buildErrorTypeFieldFetchers} loop emits
 * path/message {@code DataFetcher}s for every {@code @error} type.
 *
 * <p>The {@code attemptedId} accessor is intentionally named differently from the SDL
 * field {@code attempted: Int @field(name: "attemptedId")} on {@code FilmLookupInvalid}. It is
 * the execution-tier enforcer that {@code @field(name:)} on an {@code @error} extra field remaps
 * both the classify-time accessor check and the runtime {@code PropertyDataFetcher} to the named
 * getter (the divergent spelling {@code attempted} would resolve to nothing).
 */
public class FilmLookupInvalidIdException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Integer attemptedId;

    public FilmLookupInvalidIdException(String message) {
        this(message, null);
    }

    public FilmLookupInvalidIdException(String message, Integer attemptedId) {
        super(message);
        this.attemptedId = attemptedId;
    }

    /** The id the caller attempted to look up. Read by the {@code attempted} SDL field via {@code @field(name: "attemptedId")}. */
    public Integer getAttemptedId() {
        return attemptedId;
    }
}
