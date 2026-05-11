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
 * Well-formed {@link Coercing} with concrete type parameters. Used as the happy-path fixture
 * for the resolver's consumer-supplied path: a named class with non-erased
 * {@code Coercing<Money, Money>} resolves to a {@code ScalarResolution.Resolved} carrying
 * {@code Money} as the input Java type.
 *
 * <p>None of the methods actually parse or serialize anything; they exist only so the class
 * is loadable and the {@link Coercing} interface is satisfied. The resolver never invokes
 * them.
 */
public class MoneyCoercing implements Coercing<Money, Money> {

    @Override
    public Money serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale)
        throws CoercingSerializeException {
        return (Money) dataFetcherResult;
    }

    @Override
    public Money parseValue(Object input, GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseValueException {
        return (Money) input;
    }

    @Override
    public Money parseLiteral(Value<?> input, CoercedVariables variables,
                              GraphQLContext graphQLContext, Locale locale)
        throws CoercingParseLiteralException {
        return null;
    }
}
