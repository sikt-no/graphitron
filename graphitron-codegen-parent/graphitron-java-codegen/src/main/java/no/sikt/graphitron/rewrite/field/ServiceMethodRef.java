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
     * <p>{@code params} lists all declared parameters in declaration order. Each entry carries the
     * parameter name (requires {@code -parameters} compiler flag on the service class), the fully
     * qualified type name, and a {@link ParamKind} classifying how the value is obtained at runtime:
     * <ul>
     *   <li>{@link ParamKind#SOURCES} — the batched parent-record PK rows ({@code List<Row>}).</li>
     *   <li>{@link ParamKind#ARG} — a GraphQL field argument extracted from the DFE.</li>
     *   <li>{@link ParamKind#CONTEXT} — a context value extracted via
     *       {@code GraphitronContext.getContextArgument}.</li>
     * </ul>
     *
     * <p>{@code returnTypeName} is the fully qualified return type name (e.g.
     * {@code "java.util.List"}) as returned by {@link Class#getName()}.
     */
    record Resolved(List<ServiceParamInfo> params, String returnTypeName) implements ServiceMethodRef {}

    /**
     * The service method could not be resolved via reflection.
     *
     * <p>{@code reason} is a human-readable explanation of the failure, used in error messages.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this as an error for
     * service fields with a {@link no.sikt.graphitron.rewrite.field.ReturnTypeRef.TableBoundReturnType}.
     */
    record Unresolved(String reason) implements ServiceMethodRef {}

    /**
     * Reflection data for one parameter of a service method.
     *
     * <p>{@code name} is the parameter name from the compiled class (requires {@code -parameters}).
     *
     * <p>{@code typeName} is the fully qualified type name (e.g. {@code "org.jooq.Row"} for a
     * {@code Row} parameter, {@code "java.util.List"} for a {@code List<Row>} parameter).
     * Parameterisation is not captured — the generator uses {@code kind} to determine the wire-up.
     *
     * <p>{@code kind} classifies how the value is obtained at runtime.
     */
    record ServiceParamInfo(String name, String typeName, ParamKind kind) {}

    /**
     * Classifies how a service method parameter is bound at runtime.
     */
    enum ParamKind {
        /** The batched PK rows from the parent records. Always {@code List<Row>}. */
        SOURCES,
        /** A GraphQL field argument; extracted via {@code DataFetchingEnvironment.getArgument}. */
        ARG,
        /** A context value; extracted via {@code GraphitronContext.getContextArgument}. */
        CONTEXT
    }
}
