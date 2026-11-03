package no.sikt.graphitron.rewrite.scalarfixture;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import java.util.Locale;

/**
 * Owner class holding the {@link GraphQLScalarType} constants the resolver tests point at.
 * One constant per fixture flavour:
 *
 * <ul>
 *   <li>{@link #MONEY} — well-formed named Coercing; the happy-path consumer-supplied case.</li>
 *   <li>{@link #ANONYMOUS_MONEY} — Coercing declared via an anonymous class; type parameters
 *       are inferred from context and erase. The {@code CoercingErased.ANONYMOUS_CLASS} arm.</li>
 *   <li>{@link #RAW_MONEY} — Coercing declared without type parameters. The
 *       {@code CoercingErased.RAW_TYPE} arm.</li>
 *   <li>{@link #ERASED_NAMED_MONEY} — Coercing declared with {@code Object, Object} parameters.
 *       The {@code CoercingErased.ERASED_NAMED_CLASS} arm.</li>
 *   <li>{@link #TAGS} and {@link #TAG_LIST}: array and parameterized input types, both
 *       resolved with their full shape.</li>
 *   <li>{@link #WILDCARD_LIST} and {@link #OBJECT_ARRAY}: a wildcard or {@code Object} inside
 *       the input type. The {@code CoercingInputNotConcrete} arm.</li>
 * </ul>
 */
public final class ScalarConstants {
    private ScalarConstants() {}

    public static final GraphQLScalarType MONEY = GraphQLScalarType.newScalar()
        .name("Money")
        .description("Test fixture: well-formed custom scalar")
        .coercing(new MoneyCoercing())
        .build();

    public static final GraphQLScalarType ANONYMOUS_MONEY = GraphQLScalarType.newScalar()
        .name("AnonymousMoney")
        .description("Test fixture: Coercing declared as an anonymous class via a generic factory "
            + "method; the enclosing T captures into the anonymous class as a TypeVariable rather "
            + "than a concrete Class, so reflection's getActualTypeArguments() returns TypeVariable "
            + "and the resolver classifies as ANONYMOUS_CLASS.")
        .coercing(identityCoercing())
        .build();

    /**
     * Generic factory whose returned anonymous class's {@code Coercing<T, T>} captures {@code T}
     * as a type variable. Reflection on the resulting object's interface arguments yields a
     * {@link java.lang.reflect.TypeVariable}, not a {@link Class}; the resolver's input-type
     * recovery falls through to the {@code CoercingErased} branch and classifies as
     * {@code ANONYMOUS_CLASS} because {@code Class.isAnonymousClass()} is true.
     */
    private static <T> Coercing<T, T> identityCoercing() {
        return new Coercing<T, T>() {
            @Override
            public T serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale)
                throws CoercingSerializeException {
                return null;
            }

            @Override
            public T parseValue(Object input, GraphQLContext graphQLContext, Locale locale)
                throws CoercingParseValueException {
                return null;
            }

            @Override
            public T parseLiteral(Value<?> input, CoercedVariables variables,
                                  GraphQLContext graphQLContext, Locale locale)
                throws CoercingParseLiteralException {
                return null;
            }
        };
    }

    public static final GraphQLScalarType RAW_MONEY = GraphQLScalarType.newScalar()
        .name("RawMoney")
        .description("Test fixture: Coercing declared raw (no type parameters)")
        .coercing(new RawCoercing())
        .build();

    public static final GraphQLScalarType ERASED_NAMED_MONEY = GraphQLScalarType.newScalar()
        .name("ErasedNamedMoney")
        .description("Test fixture: named Coercing declared with Coercing<Object, Object>")
        .coercing(new ErasedNamedCoercing())
        .build();

    public static final GraphQLScalarType TAGS = GraphQLScalarType.newScalar()
        .name("Tags")
        .description("Test fixture: Coercing<String[], String[]>, a scalar over a text[] column")
        .coercing(new StringArrayCoercing())
        .build();

    public static final GraphQLScalarType TAG_LIST = GraphQLScalarType.newScalar()
        .name("TagList")
        .description("Test fixture: Coercing<List<String>, List<String>>")
        .coercing(new StringListCoercing())
        .build();

    public static final GraphQLScalarType WILDCARD_LIST = GraphQLScalarType.newScalar()
        .name("WildcardList")
        .description("Test fixture: Coercing<List<?>, List<?>>, not concrete")
        .coercing(new WildcardListCoercing())
        .build();

    public static final GraphQLScalarType OBJECT_ARRAY = GraphQLScalarType.newScalar()
        .name("ObjectArray")
        .description("Test fixture: Coercing<Object[], Object[]>, not concrete")
        .coercing(new ObjectArrayCoercing())
        .build();
}
