package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A successfully resolved reference to a Java method, with reflection data captured at parse time.
 *
 * <p>Used for all user-provided method references: {@code @service} methods, {@code @condition}
 * methods, and {@code @tableMethod} references. When resolution fails via reflection the builder
 * classifies the containing field as {@link GraphitronField.UnclassifiedField}.
 *
 * <p>{@code className} is the binary class name, e.g. {@code "com.example.FilmService"}.
 *
 * <p>{@code methodName} is the method name, e.g. {@code "getFilms"}.
 *
 * <p>{@code returnTypeName} is the fully qualified erased return type as returned by
 * {@link Class#getName()} (e.g. {@code "java.util.List"}).
 *
 * <p>{@code params} is the list of parameters in declaration order; an empty list means the
 * method takes no parameters.
 */
public record MethodRef(
    String className,
    String methodName,
    String returnTypeName,
    List<Param> params
) {

    /**
     * Reflection data for one parameter of a resolved method.
     *
     * <p>{@code name} is the parameter name from the compiled class (requires {@code -parameters}).
     *
     * <p>{@code typeName} is the fully qualified generic type name as returned by
     * {@link java.lang.reflect.Parameter#getParameterizedType()} followed by
     * {@link java.lang.reflect.Type#getTypeName()}.
     *
     * <p>{@code source} classifies where the runtime value for this parameter comes from.
     * The generator switches on the {@link ParamSource} variant to emit the correct binding
     * expression.
     */
    public record Param(String name, String typeName, ParamSource source) {}
}
