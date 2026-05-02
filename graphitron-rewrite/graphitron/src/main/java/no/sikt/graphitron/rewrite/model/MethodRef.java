package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * A resolved reference to a user-provided Java method.
 *
 * <p>Used for all user-provided method references: {@code @service} methods, {@code @condition}
 * methods, and {@code @tableMethod} references.
 *
 * <p>{@code className} is the binary class name, e.g. {@code "com.example.FilmService"}.
 *
 * <p>{@code methodName} is the method name, e.g. {@code "getFilms"}.
 *
 * <p>{@code returnType} is the structured javapoet {@link TypeName} captured from
 * {@link java.lang.reflect.Method#getGenericReturnType()} (services) or
 * {@link java.lang.reflect.Method#getReturnType()} (table methods, conditions). Carries the
 * full parameterised shape so emitters can declare matching fetcher signatures without parsing
 * a string back into a {@code TypeName}, and so the strict-return classifier check compares
 * structurally via {@link TypeName#equals(Object)}.
 *
 * <p>{@code params} is the list of parameters in declaration order; an empty list means the
 * method takes no parameters.
 *
 * <p>Implementors: {@link Basic} is the general-purpose record built from reflection data
 * (service methods, table methods) or directive configuration (join conditions).
 * {@link ConditionFilter} implements this interface directly — a {@code @condition} method IS a
 * method reference with the additional {@link WhereFilter} contract.
 */
public interface MethodRef {

    String className();
    String methodName();
    TypeName returnType();
    List<Param> params();

    /**
     * Fully qualified names of the checked exceptions the underlying Java method declares
     * (i.e. {@link java.lang.reflect.Method#getExceptionTypes()}). Empty when the method has no
     * {@code throws} clause or when this {@link MethodRef} variant doesn't reflect a Java method
     * (e.g. {@code @condition} expressions). Populated by the catalog at reflection time
     * ({@code ServiceCatalog.reflectServiceMethod} / {@code reflectTableMethod}); consumed by the
     * classifier's R12 §4 declared-exception match check
     * ({@code FieldBuilder.checkDeclaredCheckedExceptions}) so a developer method that throws
     * a checked exception with no covering {@code @error} handler is rejected at classify time
     * rather than silently flowing through {@code ErrorRouter.redact} at runtime.
     */
    default List<String> declaredExceptions() { return List.of(); }

    /**
     * Returns the single {@link Param.Sourced} parameter — the DataLoader batch-key parameter
     * whose value comes from the DataLoader {@code keys} list.
     *
     * <p>Throws if no such parameter exists. Service methods always have exactly one.
     */
    default Param.Sourced sourcedParam() {
        return params().stream()
            .filter(p -> p instanceof Param.Sourced)
            .map(p -> (Param.Sourced) p)
            .findFirst()
            .orElseThrow();
    }

    /**
     * Extracted parameters only — {@link ParamSource.Arg} and {@link ParamSource.Context} —
     * in declaration order. Skips implicit structural parameters ({@link ParamSource.Table},
     * {@link ParamSource.SourceTable}, {@link ParamSource.DslContext},
     * {@link ParamSource.Sources}).
     *
     * <p>Used by generators to build the argument list for a method call via a single
     * {@code buildArgExtraction(CallParam)} switch rather than an inline {@code ParamSource}
     * switch in each generator.
     */
    default List<CallParam> callParams() {
        return params().stream()
            .filter(p -> p.source() instanceof ParamSource.Arg || p.source() instanceof ParamSource.Context)
            .map(p -> new CallParam(callParamName(p), toCallSiteExtraction(p), false, p.typeName()))
            .toList();
    }

    /**
     * The runtime lookup key for {@link CallParam}: the GraphQL argument key for
     * {@link ParamSource.Arg} (which may diverge from the Java identifier under
     * {@code @field(name:)}), and the context key (= parameter name) for
     * {@link ParamSource.Context}. Other {@link ParamSource} variants are filtered out by
     * {@link #callParams} before reaching this helper; an unexpected variant is a programming
     * error rather than a recoverable runtime case.
     */
    private static String callParamName(Param p) {
        return switch (p.source()) {
            case ParamSource.Arg arg         -> arg.graphqlArgName();
            case ParamSource.Context ignored -> p.name();
            case ParamSource.Sources ignored     -> throw nonExtractedSource(p);
            case ParamSource.DslContext ignored  -> throw nonExtractedSource(p);
            case ParamSource.Table ignored       -> throw nonExtractedSource(p);
            case ParamSource.SourceTable ignored -> throw nonExtractedSource(p);
        };
    }

    private static IllegalStateException nonExtractedSource(Param p) {
        return new IllegalStateException(
            "callParamName called on non-Arg/Context source "
            + p.source().getClass().getSimpleName()
            + " — callParams() filter should have excluded this");
    }

    private static CallSiteExtraction toCallSiteExtraction(Param p) {
        return switch (p.source()) {
            case ParamSource.Arg arg             -> arg.extraction();
            case ParamSource.Context ignored     -> new CallSiteExtraction.ContextArg();
            case ParamSource.Sources ignored     -> throw nonExtractedSource(p);
            case ParamSource.DslContext ignored  -> throw nonExtractedSource(p);
            case ParamSource.Table ignored       -> throw nonExtractedSource(p);
            case ParamSource.SourceTable ignored -> throw nonExtractedSource(p);
        };
    }

    /**
     * The concrete record implementation for method references resolved from reflection
     * (service methods, table methods) or from directive configuration (join conditions).
     *
     * <p>When resolution fails, the builder classifies the containing field as
     * {@link GraphitronField.UnclassifiedField}.
     */
    record Basic(
        String className,
        String methodName,
        TypeName returnType,
        List<Param> params,
        List<String> declaredExceptions
    ) implements MethodRef {

        public Basic {
            declaredExceptions = List.copyOf(declaredExceptions);
        }

        /**
         * Backward-compatible 4-arg constructor for call sites that don't (yet) populate
         * declared exceptions: defaults to an empty list. {@code ServiceCatalog} reflection
         * sites use the 5-arg form to capture {@code Method.getExceptionTypes()}; tests and
         * non-reflection sites that don't care about the §4 match rule continue to use this
         * 4-arg form.
         */
        public Basic(String className, String methodName, TypeName returnType, List<Param> params) {
            this(className, methodName, returnType, params, List.of());
        }
    }

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
    sealed interface Param permits Param.Typed, Param.Sourced {
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
        record Sourced(String name, BatchKey.ParentKeyed batchKey) implements Param {
            @Override public String typeName() { return batchKey.javaTypeName(); }
            @Override public ParamSource source() { return new ParamSource.Sources(batchKey); }
        }
    }
}
