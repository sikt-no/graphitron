package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A successfully resolved reference to a Java method, with reflection data captured at parse time.
 *
 * <p>A {@code MethodRef} only appears inside {@link ReferencePathElementRef.ConditionOnlyRef} or
 * {@link ReferencePathElementRef.FkWithConditionRef} — both of which represent resolved states.
 * All fields are non-null. When condition resolution fails the builder classifies the containing
 * field as {@link GraphitronField.UnclassifiedField} rather than producing a {@code MethodRef}.
 *
 * <p>{@code className} is the binary class name derived from the {@code ExternalCodeReference}
 * input object, e.g. {@code "com.example.CustomerConditions"}.
 *
 * <p>{@code methodName} is the method name, e.g. {@code "activeCustomers"}.
 *
 * <p>{@code returnTypeName} is the fully qualified return type of the method (e.g.
 * {@code "org.jooq.Condition"}).
 *
 * <p>{@code params} is the list of parameters in declaration order; an empty list means the
 * method takes no parameters.
 */
public record MethodRef(
    String className,
    String methodName,
    String returnTypeName,
    List<ParamInfo> params
) {

    /**
     * Reflection data for one parameter of a condition or service method.
     *
     * <p>{@code typeName} is the fully qualified type name (e.g. {@code "org.jooq.DSLContext"}).
     * Used to match parameters by type when binding arguments at code-generation time.
     *
     * <p>{@code paramName} is the parameter name from the compiled class (requires
     * {@code -parameters}). Used to match parameters by name when binding arguments at
     * code-generation time.
     */
    public record ParamInfo(String typeName, String paramName) {}
}
