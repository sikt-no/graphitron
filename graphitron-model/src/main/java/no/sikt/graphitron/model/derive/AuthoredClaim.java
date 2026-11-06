package no.sikt.graphitron.model.derive;

/**
 * The directives that claim what a coordinate is, each with the name a conflict message spells.
 *
 * <p>Declaration order is the conflict reduction's naming order. It reproduces the fixed
 * per-position orders the classification walk's detector lists used to spell (service,
 * externalField, nodeId, routine at the child position; service, lookupKey, routine at the
 * query position; service, mutation at the mutation position; table, error at the type grain),
 * so a migrated conflict message names its directives byte-identically.
 */
public enum AuthoredClaim {
    SERVICE("service"),
    EXTERNAL_FIELD("externalField"),
    NODE_ID("nodeId"),
    LOOKUP_KEY("lookupKey"),
    ROUTINE("routine"),
    MUTATION("mutation"),
    TABLE("table"),
    ERROR("error");

    private final String directive;

    AuthoredClaim(String directive) {
        this.directive = directive;
    }

    /** The claiming directive's name, without the leading {@code @}; what a conflict message names. */
    public String directive() {
        return directive;
    }
}
