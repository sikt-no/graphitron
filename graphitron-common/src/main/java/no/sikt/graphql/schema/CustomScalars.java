package no.sikt.graphql.schema;

import com.apollographql.federation.graphqljava._FieldSet;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Locale;
import java.util.UUID;

import static graphql.Assert.assertShouldNeverHappen;
import static graphql.scalar.CoercingUtil.i18nMsg;
import static graphql.scalar.CoercingUtil.typeName;

public class CustomScalars  {

    /**
     * Custom scalar for the FieldSet type in Apollo Federation.
     * FieldSet replaces _FieldSet in version 2 of Apollo Federation.
     */
    public static final GraphQLScalarType FieldSet =
            GraphQLScalarType.newScalar(_FieldSet.type)
                    .name("FieldSet")
                    .build();

    /**
     * If FieldSet is not imported via the @link directive, it's name is prefixed with federation__.
     */
    public static final GraphQLScalarType Federation__FieldSet =
            GraphQLScalarType.newScalar(FieldSet)
                    .name("federation__FieldSet")
                    .build();

    /**
     * Custom ID scalar that serializes and parses values as Strings instead of Objects
     */
    public static final GraphQLScalarType GraphQLID = GraphQLScalarType.newScalar()
            .name("ID")
            .description("Overrides the default ID scalar")
            .coercing(
                    new IDStringCoercing()).build();

    private static class IDStringCoercing implements Coercing<String, String> {
        private String convertImpl(Object input) {
            if (input instanceof String) {
                return (String) input;
            }
            if (input instanceof Integer) {
                return String.valueOf(input);
            }
            if (input instanceof Long) {
                return String.valueOf(input);
            }
            if (input instanceof UUID) {
                return String.valueOf(input);
            }
            if (input instanceof BigInteger) {
                return String.valueOf(input);
            }
            return String.valueOf(input);
        }

        @NotNull
        private String serializeImpl(Object input, @NotNull Locale locale) {
            String result = String.valueOf(input);
            if (result == null) {
                throw new CoercingSerializeException(
                        "Expected type 'ID' but was '" + typeName(input) + "'."
                );
            }
            return result;
        }

        @NotNull
        private String parseValueImpl(Object input, @NotNull Locale locale) {
            String result = convertImpl(input);
            if (result == null) {
                throw new CoercingParseValueException(
                        i18nMsg(locale, "ID.notId", typeName(input))
                );
            }
            return result;
        }

        private String parseLiteralImpl(Object input, @NotNull Locale locale) {
            if (input instanceof StringValue) {
                return ((StringValue) input).getValue();
            }
            if (input instanceof IntValue) {
                return ((IntValue) input).getValue().toString();
            }
            throw new CoercingParseLiteralException(
                    i18nMsg(locale, "ID.unexpectedAstType", typeName(input))
            );
        }

        @NotNull
        private StringValue valueToLiteralImpl(Object input, @NotNull Locale locale) {
            String result = convertImpl(input);
            if (result == null) {
                assertShouldNeverHappen(i18nMsg(locale, "ID.notId", typeName(input)));
            }
            return StringValue.newStringValue(result).build();
        }

        @Override
        public String serialize(@NotNull Object dataFetcherResult, @NotNull GraphQLContext graphQLContext, @NotNull Locale locale) throws CoercingSerializeException {
            return serializeImpl(dataFetcherResult, locale);
        }

        @Override
        public String parseValue(@NotNull Object input, @NotNull GraphQLContext graphQLContext, @NotNull Locale locale) throws CoercingParseValueException {
            return parseValueImpl(input, locale);
        }

        @Override
        public @Nullable String parseLiteral(@NotNull Value<?> input, @NotNull CoercedVariables variables, @NotNull GraphQLContext graphQLContext, @NotNull Locale locale) throws CoercingParseLiteralException {
            return parseLiteralImpl(input, locale);
        }

        @Override
        public @NotNull Value<?> valueToLiteral(@NotNull Object input, @NotNull GraphQLContext graphQLContext, @NotNull Locale locale) {
            return valueToLiteralImpl(input, locale);
        }
    }
}
