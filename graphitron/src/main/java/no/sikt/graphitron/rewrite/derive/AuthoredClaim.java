package no.sikt.graphitron.rewrite.derive;

/**
 * The closed classifier vocabulary of the authored claim views
 * ({@code intent_authored_field_claim} and {@code intent_authored_type_claim}): what an
 * author's directive claims a coordinate to be. A view row's {@code classifier} literal decodes
 * into exactly one of these values; the vocabulary-enforcer test asserts the two sets stay
 * equal in both directions, so a view arm added without a value here (or the reverse) fails the
 * build rather than surfacing as a runtime decode error.
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

    /**
     * Decodes a view row's {@code classifier} literal. The literals are the enum constant names
     * by construction; an unknown literal is vocabulary drift between the DDL and this enum, a
     * build bug no author input can provoke, so it throws rather than returning an empty.
     */
    public static AuthoredClaim fromClassifier(String classifier) {
        try {
            return valueOf(classifier);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "the claim views produced classifier '" + classifier + "', which is outside the "
                + AuthoredClaim.class.getSimpleName() + " vocabulary; the view arms and this enum must move together", e);
        }
    }
}
