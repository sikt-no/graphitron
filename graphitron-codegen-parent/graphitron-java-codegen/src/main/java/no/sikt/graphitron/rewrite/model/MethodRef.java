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
     * <p>Two variants:
     * <ul>
     *   <li>{@link Typed} — all non-SOURCES parameters ({@code Arg}, {@code Context},
     *       {@code DslContext}, {@code Table}, {@code SourceTable}). The Java type is captured
     *       from reflection and stored explicitly.</li>
     *   <li>{@link Sourced} — a DataLoader batch-key parameter ({@code Sources}). The Java type
     *       is derived from the {@link BatchKey} variant so no separate {@code typeName} is
     *       stored; {@link #typeName()} and {@link #source()} are computed on demand.</li>
     * </ul>
     *
     * <p>{@code name} is the parameter name from the compiled class (requires {@code -parameters}).
     */
    public sealed interface Param permits Param.Typed, Param.Sourced {
        String name();
        String typeName();
        ParamSource source();

        /**
         * A parameter with an explicit type and source classification.
         * Used for {@link ParamSource.Arg}, {@link ParamSource.Context},
         * {@link ParamSource.DslContext}, {@link ParamSource.Table}, and
         * {@link ParamSource.SourceTable} parameters.
         *
         * <p>{@code typeName} is the fully qualified generic type name as returned by
         * {@link java.lang.reflect.Parameter#getParameterizedType()} followed by
         * {@link java.lang.reflect.Type#getTypeName()}.
         */
        record Typed(String name, String typeName, ParamSource source) implements Param {}

        /**
         * A DataLoader batch-key parameter whose Java type is fully determined by the
         * {@link BatchKey} variant — no separate {@code typeName} field is needed.
         *
         * <p>{@link #typeName()} returns the derived generic list type
         * (e.g. {@code "java.util.List<org.jooq.Row1<java.lang.Integer>>"} for
         * {@link BatchKey.RowKeyed} with one {@code Integer} PK column).
         *
         * <p>{@link #source()} returns {@code new ParamSource.Sources(batchKey)}.
         */
        record Sourced(String name, BatchKey batchKey) implements Param {
            @Override public String typeName() { return batchKey.javaTypeName(); }
            @Override public ParamSource source() { return new ParamSource.Sources(batchKey); }
        }
    }
}
