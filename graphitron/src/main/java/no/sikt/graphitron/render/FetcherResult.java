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
     * {@code return DataFetcherResult.<P>newResult().data(<local>)<tail>.build();}
     *
     * <p>{@code builderTail} is the routed-tenant sites' {@code localContext} rider, empty
     * everywhere else, which keeps the single-tenant form byte-identical.
     */
    public static CodeBlock success(TypeName valueType, String payloadLocal, CodeBlock builderTail) {
        return CodeBlock.of("return $T.<$T>newResult().data($L)$L.build();\n",
            DATA_FETCHER_RESULT, boxed(valueType), payloadLocal, builderTail);
    }

    /** {@code return DataFetcherResult.<P>newResult().data(null).build();} — the empty single shape. */
    public static CodeBlock nullData(TypeName valueType) {
        return CodeBlock.of("return $T.<$T>newResult().data(null).build();\n",
            DATA_FETCHER_RESULT, boxed(valueType));
    }
}
