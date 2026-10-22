package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

/**
 * The graphql-java result envelope every synchronous fetcher entry point declares and returns:
 * {@code DataFetcherResult<P>} as a declared type, and the success-path {@code return} that wraps
 * a local in it. One derivation, read by the renderers on the command seam and by the unmigrated
 * generator hosts alike, so the envelope's shape has one spelling while families move across.
 */
public final class FetcherResult {

    private FetcherResult() {}

    private static final ClassName DATA_FETCHER_RESULT =
        ClassName.get("graphql.execution", "DataFetcherResult");

    /** Box primitive value types so they can sit inside {@code DataFetcherResult<P>}. */
    public static TypeName boxed(TypeName valueType) {
        return valueType.isPrimitive() ? valueType.box() : valueType;
    }

    /** {@code DataFetcherResult<P>}; primitives box to their wrapper. */
    public static TypeName syncResultType(TypeName valueType) {
        return ParameterizedTypeName.get(DATA_FETCHER_RESULT, boxed(valueType));
    }

    /**
     * {@code return DataFetcherResult.<P>newResult().data(<local>)<hand-down>.build();}
     *
     * <p>{@code handDown} is the routed-tenant sites' rider, {@link TenantHandDown#none()}
     * everywhere else, which keeps the single-tenant form byte-identical.
     */
    public static CodeBlock success(TypeName valueType, String payloadLocal, TenantHandDown handDown) {
        return successOf(valueType, CodeBlock.of("$L", payloadLocal), handDown);
    }

    /**
     * {@code return DataFetcherResult.<Outcome<X>>newResult().data(new Outcome.Success<>(<local>))
     * <hand-down>.build();} — the success envelope of a fetcher on the {@code Outcome} transport.
     * {@code outcomeType} is the declared {@code Outcome<X>} and {@code successClass} its
     * {@code Success} arm.
     */
    public static CodeBlock outcomeSuccess(TypeName outcomeType, ClassName successClass,
            String payloadLocal, TenantHandDown handDown) {
        return successOf(outcomeType, CodeBlock.of("new $T<>($L)", successClass, payloadLocal), handDown);
    }

    private static CodeBlock successOf(TypeName valueType, CodeBlock data, TenantHandDown handDown) {
        return CodeBlock.of("return $T.<$T>newResult().data($L)$L.build();\n",
            DATA_FETCHER_RESULT, boxed(valueType), data, handDown.builderTail());
    }

    /** {@code return DataFetcherResult.<P>newResult().data(null).build();} — the empty single shape. */
    public static CodeBlock nullData(TypeName valueType) {
        return CodeBlock.of("return $T.<$T>newResult().data(null).build();\n",
            DATA_FETCHER_RESULT, boxed(valueType));
    }

    /**
     * {@code return DataFetcherResult.<P>newResult().data(null).errors(<errorsLocal>).build();}:
     * a field that answers with top-level errors and no value. {@code errorsLocal} names a
     * {@code List<GraphQLError>}.
     */
    public static CodeBlock errors(TypeName valueType, String errorsLocal) {
        return CodeBlock.of("return $T.<$T>newResult().data(null).errors($L).build();\n",
            DATA_FETCHER_RESULT, boxed(valueType), errorsLocal);
    }
}
