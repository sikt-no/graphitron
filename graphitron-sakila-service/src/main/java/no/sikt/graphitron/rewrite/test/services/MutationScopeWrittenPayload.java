package no.sikt.graphitron.rewrite.test.services;

/**
 * The payload of {@link MutationScopeService#writeThenReturnAFailingPayload}. Reading {@code failing}
 * throws, so a selection that includes it fails below the field after the service has written.
 */
public record MutationScopeWrittenPayload(String marker, String failing) {

    /** Always throws: the value exists to fail under the field's path. */
    @Override
    public String failing() {
        throw new IllegalStateException("mutation-scope fixture: reading the payload fails");
    }
}
