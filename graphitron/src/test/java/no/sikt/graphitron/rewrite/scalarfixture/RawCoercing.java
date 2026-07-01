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
 * Fixture: a named class declaring raw {@code Coercing} — the {@code RAW_TYPE} arm of
 * {@code CoercingDeclarationKind}. The author left the type parameters off entirely. The
 * resolver classifies this distinct from {@code ERASED_NAMED_CLASS} because the raw
 * declaration carries no parameters at all rather than parameters that resolved to
 * {@code Object}.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class RawCoercing implements Coercing {

    @Override
    public Object serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale)
        throws CoercingSerializeException {
        return dataFetcherResult;
    }

    @Override
    public Object parseValue(Object input, GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseValueException {
        return input;
    }

    @Override
    public Object parseLiteral(Value input, CoercedVariables variables,
                               GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseLiteralException {
        return null;
    }
}
