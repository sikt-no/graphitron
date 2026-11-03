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
 * Fixture: a named class declaring {@code Coercing<Object[], Object[]>}, {@code Object} as the array element, which reintroduces the {@code Object} the resolver forbids. Rejected as not concrete.
 */
public class ObjectArrayCoercing implements Coercing<Object[], Object[]> {

    @Override
    public Object[] serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale)
        throws CoercingSerializeException {
        return null;
    }

    @Override
    public Object[] parseValue(Object input, GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseValueException {
        return null;
    }

    @Override
    public Object[] parseLiteral(Value<?> input, CoercedVariables variables,
                               GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseLiteralException {
        return null;
    }
}
