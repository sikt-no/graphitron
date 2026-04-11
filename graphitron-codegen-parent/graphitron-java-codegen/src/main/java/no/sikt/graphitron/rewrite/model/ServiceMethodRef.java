package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The result of resolving a {@code @service} method via reflection at parse time.
 *
 * <p>A {@code ServiceMethodRef} is only constructed when the class and method are found via
 * reflection. When reflection fails (class not found, method not found, or incomplete service
 * reference) the containing field is classified as
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} at build time.
 *
 * <p>{@code params} lists all declared parameters in declaration order. Each entry is a
 * {@link ServiceParam} variant classifying how the value is obtained at runtime.
 *
 * <p>{@code returnTypeName} is the raw (erased) return type name (e.g.
 * {@code "java.util.List"}) as returned by {@link Class#getName()}.
 */
public record ServiceMethodRef(List<ServiceParam> params, String returnTypeName) {

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
    public sealed interface ServiceParam
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
