package no.sikt.graphitron.rewrite.test.services;

/**
 * Thrown by {@link MutationScopeService#writeTwiceThenRefuse} and claimed by the field's
 * {@code @error} channel into a payload: the field answers with business data, and its writes
 * still roll back.
 */
public class MutationScopeRefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MutationScopeRefusedException(String message) {
        super(message);
    }
}
