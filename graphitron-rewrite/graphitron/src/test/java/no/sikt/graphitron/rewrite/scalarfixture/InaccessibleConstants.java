package no.sikt.graphitron.rewrite.scalarfixture;

import graphql.Scalars;
import graphql.schema.GraphQLScalarType;

/**
 * Fixtures exercising the {@code FieldNotAccessible} arm of
 * {@code ScalarResolution.Rejected}: a package-private static field and a public-non-static
 * field. Both arms must produce {@code (isPublic, isStatic)} pairs whose values match the
 * fixture's modifiers; the test asserts that.
 */
public final class InaccessibleConstants {
    private InaccessibleConstants() {}

    /** Package-private static — should yield isPublic=false, isStatic=true. */
    static final GraphQLScalarType PACKAGE_PRIVATE_STATIC = Scalars.GraphQLString;

    /** Public non-static — should yield isPublic=true, isStatic=false. */
    public final GraphQLScalarType publicInstance = Scalars.GraphQLString;
}
