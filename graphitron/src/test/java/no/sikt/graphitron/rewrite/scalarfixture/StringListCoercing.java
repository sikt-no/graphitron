package no.sikt.graphitron.rewrite.scalarfixture;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;

import java.util.List;
import java.util.Locale;

/**
 * Fixture: a named class declaring {@code Coercing<List<String>, List<String>>}, a parameterized input type. Resolves to {@code List<String>}.
 */
public class StringListCoercing implements Coercing<List<String>, List<String>> {

    @Override
    public List<String> serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale)
        throws CoercingSerializeException {
        return null;
    }

    @Override
    public List<String> parseValue(Object input, GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseValueException {
        return null;
    }

    @Override
    public List<String> parseLiteral(Value<?> input, CoercedVariables variables,
                               GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseLiteralException {
        return null;
    }
}
