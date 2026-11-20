package no.sikt.graphitron.rewrite.model;

import com.palantir.javapoet.TypeName;

import java.util.List;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * A resolved reference to a user-provided Java method.
 *
 * <p>Used for the user-provided method references the walk reflects: {@code @condition}
 * methods, {@code @condition} references, and {@code @externalField} methods.
 *
 * <p>{@code className} is the binary class name, e.g. {@code "com.example.FilmService"}.
 *
 * <p>{@code methodName} is the method name, e.g. {@code "getFilms"}.
 *
 * <p>{@code returnType} is the structured javapoet {@link TypeName} captured from
 * {@link java.lang.reflect.Method#getReturnType()} (table methods, conditions). Carries the
 * full parameterised shape so emitters can declare matching fetcher signatures without parsing
 * a string back into a {@code TypeName}, and so the strict-return classifier check compares
 * structurally via {@link TypeName#equals(Object)}.
 *
 * <p>{@code params} is the list of parameters in declaration order; an empty list means the
 * method takes no parameters.
 *
 * <p>Sealed permits map each variant to its producer set and emit shape:
 * <ul>
 *   <li>{@link StaticOnly}: static-by-construction method references on the {@code @condition} /
 *       {@code @externalField} paths. Producers: {@code ServiceCatalog.reflectTableMethod},
 *       {@code reflectExternalField}, and {@link no.sikt.graphitron.rewrite.EnumMappingResolver}
 *       when wrapping a {@link StaticOnly} upstream.</li>
 *   <li>{@link ConditionFilter}: {@code @condition} expressions; carries no Java method, so it is
 *       never classified static-or-instance.</li>
 * </ul>
 */
public sealed interface MethodRef permits MethodRef.NonCondition, ConditionFilter {

    String className();
    String methodName();
    TypeName returnType();
    List<Param> params();

    /**
     * Fully qualified names of the checked exceptions the underlying Java method declares
     * (i.e. {@link java.lang.reflect.Method#getExceptionTypes()}). Empty when the method has no
     * {@code throws} clause or when this {@link MethodRef} variant doesn't reflect a Java method
     * (e.g. {@code @condition} expressions). Populated by the catalog at reflection time
     * ({@code ServiceCatalog.reflectTableMethod}).
     */
    default List<String> declaredExceptions() { return List.of(); }

    /**
     * Extracted parameters only ({@link ParamSource.Arg}, {@link ParamSource.Context} and the two
     * session-handle sources), in declaration order. Skips implicit structural parameters ({@link ParamSource.Table},
     * {@link ParamSource.SourceTable}, {@link ParamSource.DslContext},
     * {@link ParamSource.Sources}).
     *
     * <p>Read by consumers that build a method call's argument list off one {@link CallParam}
     * per parameter rather than an inline {@code ParamSource} switch each.
     */
    default List<CallParam> callParams() {
        return params().stream()
            .filter(p -> p.source() instanceof ParamSource.Arg || p.source() instanceof ParamSource.Context
                || p.source() instanceof ParamSource.SessionHandle
                || p.source() instanceof ParamSource.SessionHandleMember)
            .map(p -> new CallParam(callParamName(p), toCallSiteExtraction(p), false,
                p.typeName(), ((Param.Typed) p).javaType()))
            .toList();
    }

    /**
     * The runtime lookup key for {@link CallParam}: the GraphQL argument key for
     * {@link ParamSource.Arg} (which may diverge from the Java identifier under
     * {@code @field(name:)}), and the context key (= parameter name) for
     * {@link ParamSource.Context}. The head segment is the whole key even for a path binding:
     * the lookup names the outer slot, and the descent into it rides the param's
     * {@link CallSiteExtraction}. Other {@link ParamSource} variants are filtered out by
     * {@link #callParams} before reaching this helper; an unexpected variant is a programming
     * error rather than a recoverable runtime case.
     */
    private static String callParamName(Param p) {
        return switch (p.source()) {
            case ParamSource.Arg arg         -> arg.path().headName();
            case ParamSource.Context ignored -> p.name();
            case ParamSource.Sources ignored      -> throw nonExtractedSource(p);
            case ParamSource.DslContext ignored   -> throw nonExtractedSource(p);
            case ParamSource.Table ignored        -> throw nonExtractedSource(p);
            case ParamSource.SourceTable ignored  -> throw nonExtractedSource(p);
            case ParamSource.SourceColumn ignored -> throw nonExtractedSource(p);
            case ParamSource.SessionHandle ignored -> callParamNameOfSessionRead(p);
            case ParamSource.SessionHandleMember ignored -> callParamNameOfSessionRead(p);
        };
    }

    /** A session read names no argument or context key; its glue local is the Java parameter's own name. */
    private static String callParamNameOfSessionRead(Param p) {
        return p.name();
    }

    private static IllegalStateException nonExtractedSource(Param p) {
        return new IllegalStateException(
            "callParamName called on non-Arg/Context source "
            + p.source().getClass().getSimpleName()
            + " — callParams() filter should have excluded this");
    }

    private static CallSiteExtraction toCallSiteExtraction(Param p) {
        return switch (p.source()) {
            // The whole binding, not the leaf transform alone: a dotted path's descent rides the
            // NestedInputField wrapper ParamSource.Arg mints, and reading extraction() directly here
            // is what made a dotted @condition argMapping pass its outer argument instead.
            case ParamSource.Arg arg             -> arg.callSiteExtraction();
            case ParamSource.Context ignored     -> new CallSiteExtraction.ContextArg();
            case ParamSource.Sources ignored      -> throw nonExtractedSource(p);
            case ParamSource.DslContext ignored   -> throw nonExtractedSource(p);
            case ParamSource.Table ignored        -> throw nonExtractedSource(p);
            case ParamSource.SourceTable ignored  -> throw nonExtractedSource(p);
            case ParamSource.SourceColumn ignored -> throw nonExtractedSource(p);
            case ParamSource.SessionHandle ignored -> new CallSiteExtraction.SessionHandleRead();
            case ParamSource.SessionHandleMember m -> new CallSiteExtraction.SessionMemberRead(m.member());
        };
    }

    /**
     * Sub-interface for the {@code @condition}-incompatible population, {@link StaticOnly}.
     * Consumers that post-process method parameters narrow their parameter type to
     * {@link NonCondition} so that passing a {@link ConditionFilter} is a compile error rather
     * than a runtime throw.
     */
    sealed interface NonCondition extends MethodRef permits StaticOnly {}

    /**
     * Static-by-construction method references: {@code @condition}, {@code @externalField},
     * and enum-mapping wrappers around either. The variant identity IS the static-call-shape
     * guarantee: no {@code callShape()} accessor exists, so any consumer reading a
     * {@link StaticOnly} can emit {@code ClassName.method(...)} unconditionally.
     */
    record StaticOnly(
        String className,
        String methodName,
        TypeName returnType,
        List<Param> params,
        List<String> declaredExceptions
    ) implements NonCondition {

        public StaticOnly {
            declaredExceptions = List.copyOf(declaredExceptions);
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
     *       is derived from the {@code (wrap, columns, container)} triple so no separate
     *       {@code typeName} is stored; {@link #typeName()} and {@link #source()} are computed
     *       on demand.</li>
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
         *
         * <p>{@code javaType} is the structured JavaPoet {@link TypeName} captured from
         * {@link java.lang.reflect.Parameter#getParameterizedType()}. Stored alongside the
         * string-form {@code typeName} so emitters that need a JavaPoet AST (factory parameter
         * lists, {@code $T.class} cast literals) do not have to re-parse the rendered string,
         * as {@link MethodRef#returnType()} does.
         */
        record Typed(String name, String typeName, TypeName javaType, ParamSource source) implements Param {

            /**
             * Convenience constructor for test scaffolding that builds a {@link Typed} without the
             * full reflected {@link TypeName}: {@code javaType} is best-effort-derived from the
             * {@code typeName} string via {@link com.palantir.javapoet.ClassName#bestGuess}.
             * Production paths in {@code ServiceCatalog} always pass the structured {@code javaType}
             * captured from reflection.
             */
            public Typed(String name, String typeName, ParamSource source) {
                this(name, typeName, deriveJavaType(typeName), source);
            }

            private static TypeName deriveJavaType(String typeName) {
                int lt = typeName.indexOf('<');
                String raw = lt < 0 ? typeName : typeName.substring(0, lt);
                return com.palantir.javapoet.ClassName.bestGuess(raw);
            }
        }

        /**
         * A DataLoader batch-key parameter whose Java type is fully determined by the
         * {@code (wrap, columns, container)} triple: {@link SourceKey.Wrap} carries the per-row
         * shape (Row / Record / typed TableRecord), {@code columns} is the parent-side PK/FK
         * column tuple driving the key arity and type args, and
         * {@link LoaderRegistration.Container} carries the mapped/positional axis.
         *
         * <p>{@link #typeName()} returns the derived generic list/set type
         * (e.g. {@code "java.util.List<org.jooq.Row1<java.lang.Integer>>"} for a
         * {@code Wrap.Row} + single-column + {@code POSITIONAL_LIST} triple).
         *
         * <p>{@link #source()} returns {@code new ParamSource.Sources(wrap, columns, container)}.
         */
        record Sourced(
                String name,
                SourceKey.Wrap wrap,
                List<ColumnRef> columns,
                LoaderRegistration.Container container) implements Param {
            public Sourced {
                java.util.Objects.requireNonNull(wrap, "wrap");
                java.util.Objects.requireNonNull(container, "container");
                if (columns == null || columns.isEmpty()) {
                    throw new IllegalArgumentException(
                        "MethodRef.Param.Sourced requires a non-empty columns list");
                }
                columns = List.copyOf(columns);
            }
            @Override public String typeName() { return computeJavaTypeName(wrap, columns, container); }
            @Override public ParamSource source() { return new ParamSource.Sources(wrap, columns, container); }

            private static String computeJavaTypeName(
                    SourceKey.Wrap wrap, List<ColumnRef> columns, LoaderRegistration.Container container) {
                String outer = container == LoaderRegistration.Container.MAPPED_SET ? "Set" : "List";
                String element = switch (wrap) {
                    case SourceKey.Wrap.Row r       -> jooqShape("Row", columns);
                    case SourceKey.Wrap.Record r    -> jooqShape("Record", columns);
                    case SourceKey.Wrap.TableRecord tr -> tr.className().canonicalName();
                };
                return "java.util." + outer + "<" + element + ">";
            }
            private static String jooqShape(String shape, List<ColumnRef> cols) {
                String args = cols.stream()
                    .map(ColumnRef::columnClass)
                    .collect(java.util.stream.Collectors.joining(", "));
                return "org.jooq." + shape + cols.size() + "<" + args + ">";
            }
        }
    }
}
