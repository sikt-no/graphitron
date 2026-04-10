package no.sikt.graphitron.rewrite.field;

import java.util.List;

/**
 * The outcome of resolving a {@code @service} method via reflection at parse time.
 *
 * <p>{@link Resolved} carries the full parameter list in declaration order; the generator uses it
 * to emit arg-extraction statements and build the service call. {@link Unresolved} is used when
 * the class could not be loaded or the method could not be found — the validator reports an error
 * and the generator falls back to an {@link UnsupportedOperationException} stub.
 */
public sealed interface ServiceMethodRef
    permits ServiceMethodRef.Resolved, ServiceMethodRef.Unresolved {

    /**
     * The service method was successfully resolved via reflection.
     *
     * <p>{@code params} lists all declared parameters in declaration order. Each entry is a
     * {@link ServiceParam} variant classifying how the value is obtained at runtime.
     *
     * <p>{@code returnTypeName} is the raw (erased) return type name (e.g.
     * {@code "java.util.List"}) as returned by {@link Class#getName()}.
     */
    record Resolved(List<ServiceParam> params, String returnTypeName) implements ServiceMethodRef {}

    /**
     * The service method could not be resolved via reflection.
     *
     * <p>{@code reason} is a human-readable explanation of the failure, used in error messages.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this as an error for
     * service fields with a {@link no.sikt.graphitron.rewrite.field.ReturnTypeRef.TableBoundReturnType}.
     */
    record Unresolved(String reason) implements ServiceMethodRef {}

    /**
     * One parameter of a service method, classified by how its value is obtained at runtime.
     *
     * <ul>
     *   <li>{@link SourcesParam} — the batched parent-record keys; the {@link SourcesRef}
     *       classifies the element type of the {@code List<?>} (Row-keyed, Record-keyed, or
     *       TableRecord-keyed).</li>
     *   <li>{@link ArgParam} — a GraphQL field argument extracted from the DFE.</li>
     *   <li>{@link ContextParam} — a context value extracted via
     *       {@code GraphitronContext.getContextArgument}.</li>
     * </ul>
     */
    sealed interface ServiceParam
        permits ServiceParam.SourcesParam, ServiceParam.ArgParam, ServiceParam.ContextParam {

        /** The parameter name from the compiled class (requires {@code -parameters}). */
        String name();

        /**
         * The batched parent-record keys; element type is classified by {@link SourcesRef}.
         * The generator passes {@code keys} in the service call and builds the DataLoader key
         * expression from the {@link SourcesRef} variant.
         */
        record SourcesParam(String name, SourcesRef sourcesRef) implements ServiceParam {}

        /**
         * A GraphQL field argument; extracted via {@code DataFetchingEnvironment.getArgument}.
         *
         * <p>{@code typeName} is the fully qualified generic type name as returned by
         * {@link java.lang.reflect.Parameter#getParameterizedType()} followed by
         * {@link java.lang.reflect.Type#getTypeName()}.
         */
        record ArgParam(String name, String typeName) implements ServiceParam {}

        /**
         * A context value; extracted via {@code GraphitronContext.getContextArgument}.
         *
         * <p>{@code typeName} is the fully qualified generic type name as returned by
         * {@link java.lang.reflect.Parameter#getParameterizedType()} followed by
         * {@link java.lang.reflect.Type#getTypeName()}.
         */
        record ContextParam(String name, String typeName) implements ServiceParam {}
    }
}
