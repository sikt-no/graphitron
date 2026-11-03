package no.sikt.graphitron.rewrite.scalarfixture;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;

import java.util.Locale;

/**
 * Fixture: a named class declaring {@code Coercing<String[], String[]>}, an array input type, the shape a scalar over a {@code text[]} column takes. Resolves to {@code String[]}.
 */
public class StringArrayCoercing implements Coercing<String[], String[]> {

    @Override
    public String[] serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale)
        throws CoercingSerializeException {
        return null;
    }

    @Override
    public String[] parseValue(Object input, GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseValueException {
        return null;
    }

    @Override
    public String[] parseLiteral(Value<?> input, CoercedVariables variables,
                               GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseLiteralException {
        return null;
    }
}
