package no.sikt.graphitron.rewrite.test.scalars;

import graphql.schema.GraphQLScalarType;

/**
 * A consumer's own scalar constant, beside the library's: what {@code @scalarType(scalar:)} may name
 * that no table of extended scalars could list.
 */
public final class Scalars {

    private Scalars() {}

    /** A decimal under a name of the consumer's own, coerced as graphql-java coerces a float. */
    public static final GraphQLScalarType MONEY = GraphQLScalarType.newScalar()
        .name("Money")
        .coercing(graphql.Scalars.GraphQLFloat.getCoercing())
        .build();
}
