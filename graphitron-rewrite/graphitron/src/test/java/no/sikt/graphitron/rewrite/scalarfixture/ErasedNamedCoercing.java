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
 * Fixture: a named class declaring {@code Coercing<Object, Object>} — the
 * {@code ERASED_NAMED_CLASS} arm of {@code CoercingDeclarationKind}. The author wrote a
 * named class but their generic arguments resolved to {@code Object} (typo, missing
 * parameter, etc.). The resolver classifies this distinct from {@code RAW_TYPE} because
 * the parameters are present, just wrong.
 */
public class ErasedNamedCoercing implements Coercing<Object, Object> {

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
    public Object parseLiteral(Value<?> input, CoercedVariables variables,
                               GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseLiteralException {
        return null;
    }
}
