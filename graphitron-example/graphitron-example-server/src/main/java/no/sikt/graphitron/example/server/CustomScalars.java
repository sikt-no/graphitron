package no.sikt.graphitron.example.server;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.util.Locale;

import static graphql.scalars.util.Kit.typeName;

public class CustomScalars {

    public static final GraphQLScalarType GraphQLLocalDateTime;

    static {
        Coercing<LocalDateTime, String> coercing = new Coercing<>() {

            @Override
            public LocalDateTime parseLiteral(@NotNull Value<?> input, @NotNull CoercedVariables variables,
                                              @NotNull GraphQLContext graphQLContext, @NotNull Locale locale)
                    throws CoercingParseLiteralException {

                if (!(input instanceof StringValue)) {
                    throw new CoercingParseLiteralException(
                            "Expected AST type 'StringValue' but was '" + typeName(input) + "'."
                    );
                }
                try {
                    return LocalDateTime.parse(((StringValue) input).getValue());
                } catch (Exception e) {
                    throw new CoercingParseLiteralException(e.getMessage());
                }
            }

            @Override
            public String serialize(@NotNull Object dataFetcherResult, @NotNull GraphQLContext graphQLContext,
                                    @NotNull Locale locale) throws CoercingSerializeException {
                if (dataFetcherResult instanceof LocalDateTime) {
                    return dataFetcherResult.toString();
                }
                throw new CoercingSerializeException(
                        "Expected type 'LocalDateTime' but was '" + typeName(dataFetcherResult) + "'."
                );
            }
        };

        GraphQLLocalDateTime = GraphQLScalarType.newScalar()
                .name("LocalDateTime")
                .description("A custom LocalDateTime scalar")
                .coercing(coercing)
                .build();
    }
}
