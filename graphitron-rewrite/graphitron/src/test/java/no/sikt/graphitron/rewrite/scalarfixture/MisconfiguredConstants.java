package no.sikt.graphitron.rewrite.scalarfixture;

import graphql.schema.GraphQLScalarType;

/**
 * Fixtures exercising the {@code NullAtCodegen} and {@code NotAScalarType} arms of
 * {@code ScalarResolution.Rejected}.
 */
public final class MisconfiguredConstants {
    private MisconfiguredConstants() {}

    /** A {@code public static final GraphQLScalarType} that evaluates to {@code null} at codegen. */
    public static final GraphQLScalarType NULL_SCALAR = null;

    /** A {@code public static final} field whose declared type is not {@code GraphQLScalarType}. */
    public static final String NOT_A_SCALAR = "not a scalar type";
}
