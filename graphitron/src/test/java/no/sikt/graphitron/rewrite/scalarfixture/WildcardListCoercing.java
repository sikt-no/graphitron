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
 * Fixture: a named class declaring {@code Coercing<List<?>, List<?>>}, a wildcard inside the generic, which names no single Java type. Rejected as not concrete.
 */
public class WildcardListCoercing implements Coercing<List<?>, List<?>> {

    @Override
    public List<?> serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale)
        throws CoercingSerializeException {
        return null;
    }

    @Override
    public List<?> parseValue(Object input, GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseValueException {
        return null;
    }

    @Override
    public List<?> parseLiteral(Value<?> input, CoercedVariables variables,
                               GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseLiteralException {
        return null;
    }
}
