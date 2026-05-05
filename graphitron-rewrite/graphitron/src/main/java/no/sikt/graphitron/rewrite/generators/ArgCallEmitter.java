package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.toCamelCase;

/**
 * Emits argument-list and per-argument extraction code for condition-method calls.
 *
 * <p>Consumed by {@link TypeFetcherGenerator} (filter/where composition in fetcher bodies)
 * and by {@code InlineTableFieldEmitter} (G5 inline-subquery WHERE). Extracted from
 * {@code TypeFetcherGenerator} so both consumers can share a single emission surface for
 * the {@code <ConditionsClass>.<method>(table, argN...)} call pattern.
 */
public final class ArgCallEmitter {

    private ArgCallEmitter() {}

    /**
     * Builds the argument list for one condition method call: the table-alias local
     * first, then one arg per {@link CallParam}. The {@code conditionsClassName} is used
     * by {@link CallSiteExtraction.TextMapLookup} to reference a static map field on the
     * class. {@code srcAlias} is the name of the jOOQ table-alias local variable in the
     * caller's scope (e.g. {@code filmTable}) — passed through to
     * {@link #buildArgExtraction} so the {@code JooqConvert} branch resolves the same
     * local.
     */
    public static CodeBlock buildCallArgs(TypeFetcherEmissionContext ctx, List<CallParam> params, String conditionsClassName, String srcAlias) {
        return buildCallArgs(ctx, params, conditionsClassName, srcAlias, null);
    }

    /**
     * Variant that accepts a {@link CompositeDecodeHelperRegistry}. When non-null, composite-key
     * NodeId decode chains (arity > 1) are lifted to per-class private helpers registered through
     * the collector; the call site emits {@code <helper>(<wireExpr>)} instead of the inline
     * decode-and-project ternary. Other extraction shapes are unaffected.
     *
     * <p>Currently only {@link QueryConditionsGenerator} passes a non-null registry; the
     * remaining call sites (Inline*, SplitRows*, TypeFetcher) use the inline form.
     */
    public static CodeBlock buildCallArgs(TypeFetcherEmissionContext ctx, List<CallParam> params,
            String conditionsClassName, String srcAlias, CompositeDecodeHelperRegistry registry) {
        var args = CodeBlock.builder();
        args.add("$L", srcAlias);
        for (var param : params) {
            args.add(", $L", buildArgExtraction(ctx, param, conditionsClassName, srcAlias, registry));
        }
        return args.build();
    }

    /**
     * Builds the argument list for a method-backed call (root {@code @service} or
     * {@code @tableMethod} fetcher), iterating {@link MethodRef#params()} in declaration
     * order and emitting one expression per {@link ParamSource} variant.
     *
     * <p>Unlike {@link #buildCallArgs}, there is no implicit first argument: the helper
     * emits exactly the comma-separated argument expressions in user-declared order,
     * letting the caller wrap with whatever surrounding code (a {@code dsl} local,
     * a projection, a {@code return} statement) the per-leaf shape requires.
     *
     * <p>Per-{@link ParamSource} emission:
     * <ul>
     *   <li>{@link ParamSource.Arg} — delegates to {@link #buildArgExtraction} so the
     *       five-way {@link CallSiteExtraction} switch ({@code Direct}, {@code EnumValueOf},
     *       {@code TextMapLookup}, {@code ContextArg}, {@code JooqConvert}, {@code NestedInputField})
     *       is reused. The {@code srcAlias} threaded into {@code buildArgExtraction} is
     *       the table local for {@code QueryTableMethodTableField} (the {@code var table = ...}
     *       declared by the per-leaf fetcher) and {@code null} for the service variants
     *       (no source-table context at the root).</li>
     *   <li>{@link ParamSource.Context} — {@code graphitronContext(env).getContextArgument(env, name)}.</li>
     *   <li>{@link ParamSource.DslContext} — literal {@code dsl}; the per-leaf fetcher
     *       declares the local before calling this helper.</li>
     *   <li>{@link ParamSource.Table} — the supplied {@code tableExpression} {@code CodeBlock}
     *       (e.g. {@code Tables.FILM} for {@code @tableMethod}); {@code null} for service variants.</li>
     *   <li>{@link ParamSource.Sources} / {@link ParamSource.SourceTable} — never reached:
     *       the classifier (FieldBuilder Invariants §2) and the {@code @tableMethod} reflection
     *       check (ServiceCatalog) prevent a root leaf from carrying these. The helper throws
     *       {@link IllegalStateException} if it sees one.</li>
     * </ul>
     *
     * @param method            the developer method to call.
     * @param tableExpression   expression emitted at the {@link ParamSource.Table} slot;
     *                          must be non-null when the method declares a {@code Table<?>} parameter.
     * @param conditionsClassName  target for {@link CallSiteExtraction.TextMapLookup}; may be null
     *                             when no {@code TextMapLookup} extractions exist on the method.
     */
    public static CodeBlock buildMethodBackedCallArgs(TypeFetcherEmissionContext ctx, MethodRef method, CodeBlock tableExpression, String conditionsClassName) {
        return buildMethodBackedCallArgs(ctx, method, tableExpression, null, conditionsClassName);
    }

    /**
     * Variant that accepts a {@code sourcesExpression} — the {@link CodeBlock} to emit at the
     * {@link ParamSource.Sources} slot. Use this when emitting a child {@code @service} rows-
     * method body where the {@code keys} parameter (or a converted form of it) is the value
     * supplied at the Sources slot. When {@code null}, Sources is rejected as in the legacy
     * two-arg overload.
     */
    public static CodeBlock buildMethodBackedCallArgs(TypeFetcherEmissionContext ctx, MethodRef method, CodeBlock tableExpression,
            CodeBlock sourcesExpression, String conditionsClassName) {
        var args = CodeBlock.builder();
        boolean first = true;
        for (var param : method.params()) {
            if (!first) args.add(", ");
            first = false;
            args.add(emitForParam(ctx, param, tableExpression, sourcesExpression, conditionsClassName));
        }
        return args.build();
    }

    private static CodeBlock emitForParam(TypeFetcherEmissionContext ctx, MethodRef.Param param, CodeBlock tableExpression,
            CodeBlock sourcesExpression, String conditionsClassName) {
        var source = param.source();
        return switch (source) {
            case ParamSource.Arg arg -> buildArgExtraction(
                ctx,
                new CallParam(arg.graphqlArgName(), arg.extraction(), false, param.typeName()),
                conditionsClassName,
                null);
            case ParamSource.Context ignored ->
                CodeBlock.of("$L.getContextArgument(env, $S)", ctx.graphitronContextCall(), param.name());
            case ParamSource.DslContext ignored ->
                CodeBlock.of("dsl");
            case ParamSource.Table ignored -> {
                if (tableExpression == null) {
                    throw new IllegalStateException(
                        "ParamSource.Table reached buildMethodBackedCallArgs without a tableExpression: param '"
                        + param.name() + "'");
                }
                yield tableExpression;
            }
            case ParamSource.Sources ignored -> {
                if (sourcesExpression == null) {
                    throw new IllegalStateException(
                        "ParamSource.Sources reached buildMethodBackedCallArgs without a sourcesExpression — "
                            + "root-level @service must reject this at classifier time (Invariants §2); "
                            + "child-level rows-method emitters must pass a sourcesExpression: param '"
                            + param.name() + "'");
                }
                yield sourcesExpression;
            }
            case ParamSource.SourceTable ignored ->
                throw new IllegalStateException(
                    "ParamSource.SourceTable reached buildMethodBackedCallArgs — SourceTable is a child-field concept, unreachable at root: param '"
                    + param.name() + "'");
        };
    }

    public static CodeBlock buildArgExtraction(TypeFetcherEmissionContext ctx, CallParam param, String conditionsClassName, String srcAlias) {
        return buildArgExtraction(ctx, param, conditionsClassName, srcAlias, null);
    }

    /**
     * Registry-aware variant; see
     * {@link #buildCallArgs(TypeFetcherEmissionContext, List, String, String, CompositeDecodeHelperRegistry)}.
     */
    public static CodeBlock buildArgExtraction(TypeFetcherEmissionContext ctx, CallParam param,
            String conditionsClassName, String srcAlias, CompositeDecodeHelperRegistry registry) {
        return switch (param.extraction()) {
            case CallSiteExtraction.Direct ignored ->
                CodeBlock.of("env.getArgument($S)", param.name());
            case CallSiteExtraction.EnumValueOf ev -> {
                var enumClass = ClassName.bestGuess(ev.enumClassName());
                yield CodeBlock.of(
                    "env.getArgument($S) != null ? $T.valueOf(env.<$T>getArgument($S)) : null",
                    param.name(), enumClass, String.class, param.name());
            }
            case CallSiteExtraction.TextMapLookup tl ->
                CodeBlock.of(
                    "env.getArgument($S) != null ? $T.$L.get(env.<$T>getArgument($S)) : null",
                    param.name(), ClassName.bestGuess(conditionsClassName), tl.mapFieldName(),
                    String.class, param.name());
            case CallSiteExtraction.ContextArg ignored ->
                CodeBlock.of("$L.getContextArgument(env, $S)", ctx.graphitronContextCall(), param.name());
            case CallSiteExtraction.JooqConvert jc -> param.list()
                ? CodeBlock.of("$L.stream().map($L.$L.getDataType()::convert).toList()",
                    toCamelCase(param.name()) + "Keys", srcAlias, jc.columnJavaName())
                : CodeBlock.of("$L.$L.getDataType().convert((String) env.getArgument($S))",
                    srcAlias, jc.columnJavaName(), param.name());
            case CallSiteExtraction.NestedInputField nif ->
                buildNestedInputFieldExtraction(nif.outerArgName(), nif.path(), nif.leaf(),
                    param.typeName(), param.list(), registry);
            case CallSiteExtraction.NodeIdDecodeKeys nidk ->
                buildNodeIdDecodeExtraction(
                    CodeBlock.of("env.getArgument($S)", param.name()),
                    nidk, param.typeName(), param.list(), registry);
        };
    }

    /**
     * Emits a NodeId-decode chain for either a top-level argument or a Map-traversal leaf.
     * {@code wireExpr} is the expression that yields the wire-format value (a {@code String}
     * for scalar, a {@code List<String>} for list). The decode helper resolves to
     * {@code NodeIdEncoder.decode<TypeName>(...)}; on a {@code null} return the
     * {@link CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement} arm filters the bad
     * element out (list) or returns {@code null} (scalar), while
     * {@link CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch} raises a
     * {@code GraphqlErrorException}.
     *
     * <p>Body shapes:
     * <ul>
     *   <li>Arity-1 list: {@code wire.stream().map(decode).filter(Objects::nonNull).map(r -> r.value1()).collect(toList())}</li>
     *   <li>Arity-1 scalar: {@code wire == null ? null : decode(wire) == null ? null : decode(wire).value1()}
     *       — the double-call is acceptable; jOOQ records are cheap to construct.</li>
     *   <li>Arity-N list: {@code wire.stream().map(decode).filter(Objects::nonNull).collect(toList())}
     *       — typed {@link org.jooq.Record} list; the body-emitter casts to {@link org.jooq.Row}
     *       at the predicate site.</li>
     *   <li>Arity-N scalar: {@code wire == null ? null : decode(wire)} — the body-emitter
     *       casts to {@link org.jooq.Row} at the predicate site.</li>
     * </ul>
     *
     * <p>The {@code Throw} arm replaces {@code Objects::nonNull} guarding with an explicit
     * {@code GraphqlErrorException} wrapper; the wrapper appears once per element.
     */
    private static CodeBlock buildNodeIdDecodeExtraction(CodeBlock wireExpr,
            CallSiteExtraction.NodeIdDecodeKeys nidk, String leafTypeName, boolean list,
            CompositeDecodeHelperRegistry registry) {
        var decode = nidk.decodeMethod();
        var encoderClass = decode.encoderClass();
        String methodName = decode.methodName();
        int arity = decode.outputColumnShape().size();
        boolean throwOnMismatch = nidk instanceof CallSiteExtraction.ThrowOnMismatch;

        // Composite (arity > 1) NodeId decoding lifts to a per-class private helper when the
        // caller provides a registry. The helper bakes in the wire-shape guard, decode call, and
        // Record<N>::valuesRow projection, so the call site collapses to <name>(wireExpr).
        // Arity-1 paths and null-registry callers fall through to the inline form below.
        if (registry != null && arity > 1) {
            var mode = throwOnMismatch
                ? CompositeDecodeHelperRegistry.Mode.THROW
                : CompositeDecodeHelperRegistry.Mode.SKIP;
            String helperName = registry.register(decode, mode, list);
            return CodeBlock.of("$L($L)", helperName, wireExpr);
        }

        ClassName objects = ClassName.get(java.util.Objects.class);
        ClassName collectors = ClassName.get(java.util.stream.Collectors.class);

        if (list) {
            // Pattern: (wire) instanceof List<?> _nl ? _nl.stream()
            //          .map(decode).filter(nonNull)[.map(value1) | .map(RecordN::valuesRow)]
            //          .collect(toList()) : null
            // wireExpr is wrapped in outer parens so the instanceof binds at the right
            // precedence (ternary `?:` is right-associative; instanceof binds tighter).
            var stream = CodeBlock.builder()
                .add("($L) instanceof $T<?> _nl ? _nl.stream().map(s -> $T.$L((String) s))",
                    wireExpr, ClassName.get(List.class),
                    encoderClass, methodName);
            if (throwOnMismatch) {
                stream.add(".map(r -> { if (r == null) throw new $T($S); return r; })",
                    ClassName.get("graphql", "GraphqlErrorException"),
                    "Decoded NodeId did not match the expected type for this argument");
            } else {
                stream.add(".filter($T::nonNull)", objects);
            }
            if (arity == 1) {
                // Arity-1: project to the typed scalar, body emits column.in(List<T>).
                stream.add(".map(r -> r.value1())");
            } else {
                // Arity-N: project the typed RecordN to its typed Row<N><...> via the
                // RecordN-typed method reference. decode.returnType() is RecordN<T1, ..., TN>,
                // so the resulting stream element is Row<N><T1, ..., TN>; body can do
                // DSL.row(cols).in(List<Row<N><T1, ..., TN>>) without coercion.
                ClassName recordN = ClassName.get("org.jooq", "Record" + arity);
                stream.add(".map($T::valuesRow)", recordN);
            }
            stream.add(".collect($T.toList()) : null", collectors);
            return stream.build();
        }

        // scalar -- wrap wireExpr in parens for ternary precedence.
        // Skip arm: scalar case treats null decode as "no match" (caller's nullable arg).
        // Throw arm: a null decode raises a GraphqlErrorException via a helper lambda; the
        // body shape uses a (((Supplier<X>) () -> { throw ...; }).get()) trick to keep the
        // expression form, but inverting through a helper static is cleaner. Generated code
        // calls NodeIdEncoder.decode<TypeName>(_s) and wraps via a guard.
        if (arity == 1) {
            // Java 17 compatibility: pattern matching with parameterised types is Java 21+.
            // Cast to Object first so the {@code instanceof Record1 _r} pattern is conditional
            // (raw {@code Record1} is a strict subtype of {@code Object}); without the cast,
            // {@code instanceof Record1<X> _r} is an unconditional pattern requiring Java 21+.
            ClassName recordRaw = ClassName.get("org.jooq", "Record1");
            ClassName valueClass = ClassName.bestGuess(decode.outputColumnShape().get(0).columnClass());
            if (throwOnMismatch) {
                return CodeBlock.of(
                    "($L) instanceof String _s ? (((Object) $T.$L(_s)) instanceof $T _r ? ($T) _r.value1() : "
                    + "(($T<?>) () -> { throw new $T($S); }).get()) : null",
                    wireExpr, encoderClass, methodName, recordRaw, valueClass,
                    ClassName.get("java.util.function", "Supplier"),
                    ClassName.get("graphql", "GraphqlErrorException"),
                    "Decoded NodeId did not match the expected type for this argument");
            }
            return CodeBlock.of("($L) instanceof String _s && ((Object) $T.$L(_s)) instanceof $T _r ? ($T) _r.value1() : null",
                wireExpr, encoderClass, methodName, recordRaw, valueClass);
        }
        // arity > 1: raw RecordN pattern (Java-17 compatible, parameterized instanceof patterns
        // are Java 21+) plus a cast to the typed Row<N><...>. The cast is unchecked but sound:
        // decode.returnType() is RecordN<T1, ..., TN>, so _r.valuesRow() at runtime is a
        // Row<N><T1, ..., TN>; the cast just teaches javac the parameterization. Mirrors the
        // arity-1 raw-pattern + cast form a few lines up.
        ClassName recordRaw = ClassName.get("org.jooq", "Record" + arity);
        TypeName rowTyped = typedRowName(decode.outputColumnShape());
        if (throwOnMismatch) {
            return CodeBlock.of(
                "($L) instanceof String _s ? (((Object) $T.$L(_s)) instanceof $T _r ? ($T) _r.valuesRow() : "
                + "(($T<?>) () -> { throw new $T($S); }).get()) : null",
                wireExpr, encoderClass, methodName, recordRaw, rowTyped,
                ClassName.get("java.util.function", "Supplier"),
                ClassName.get("graphql", "GraphqlErrorException"),
                "Decoded NodeId did not match the expected type for this argument");
        }
        return CodeBlock.of(
            "($L) instanceof String _s && ((Object) $T.$L(_s)) instanceof $T _r ? ($T) _r.valuesRow() : null",
            wireExpr, encoderClass, methodName, recordRaw, rowTyped);
    }

    /** Builds {@code Row<N><T1, ..., TN>} for the cast target. */
    private static TypeName typedRowName(List<no.sikt.graphitron.rewrite.model.ColumnRef> columns) {
        int n = columns.size();
        ClassName rowN = ClassName.get("org.jooq", "Row" + n);
        TypeName[] typeArgs = new TypeName[n];
        for (int i = 0; i < n; i++) {
            typeArgs[i] = ClassName.bestGuess(columns.get(i).columnClass());
        }
        return ParameterizedTypeName.get(rowN, typeArgs);
    }

    /**
     * Emits a null-safe nested-Map traversal expression for
     * {@link CallSiteExtraction.NestedInputField}. For {@code path = [k1, k2, ..., kN]} the
     * generated expression is a chain of {@code instanceof Map<?,?>} ternaries:
     *
     * <pre>
     *     env.getArgument("outer") instanceof Map&lt;?, ?&gt; _m1
     *         ? (_m1.get("k1") instanceof Map&lt;?, ?&gt; _m2
     *             ? (... ? (LeafType) _mN.get("kN") : null)
     *             : null)
     *         : null
     * </pre>
     *
     * <p>Pattern-variable bindings {@code _m1..._mN} are scoped to the ternary's "then" branch,
     * so peer expressions in the same method call (multiple condition args) may reuse the same
     * binding names without conflict.
     *
     * <p>The leaf cast uses the raw component of {@code leafTypeName} (everything up to the first
     * {@code <}) so generic type parameters do not appear in the cast target. Since
     * {@code Map.get(Object)} returns {@code Object} and the condition method signature expects
     * the declared type, an unchecked raw-type cast is acceptable; if a parameterized leaf type
     * ever requires it, callers can suppress warnings at the enclosing method.
     */
    private static CodeBlock buildNestedInputFieldExtraction(String outerArgName, List<String> path,
            CallSiteExtraction leaf, String leafTypeName, boolean list,
            CompositeDecodeHelperRegistry registry) {
        // For a Direct leaf the Map.get value is cast directly to the parameter type. For a
        // NodeIdDecodeKeys leaf the leaf cast is omitted -- the decode chain takes Object and
        // its own instanceof guard validates the runtime shape -- so the inner pattern stays
        // "conditional" under -source 17 (no `(List<String>) ... instanceof List` chain).
        boolean nodeIdLeaf = leaf instanceof CallSiteExtraction.NodeIdDecodeKeys;
        CodeBlock root = CodeBlock.of("env.getArgument($S)", outerArgName);

        if (nodeIdLeaf) {
            CodeBlock mapChain = buildMapChain(root, path, 0, /* leafType= */ null);
            return buildNodeIdDecodeExtraction(mapChain, (CallSiteExtraction.NodeIdDecodeKeys) leaf,
                leafTypeName, list, registry);
        }
        ClassName rawLeaf = ClassName.bestGuess(rawComponent(leafTypeName));
        TypeName castTarget = list
            ? ParameterizedTypeName.get(ClassName.get(List.class), rawLeaf)
            : rawLeaf;
        return buildMapChain(root, path, 0, castTarget);
    }

    private static CodeBlock buildMapChain(CodeBlock currentExpr, List<String> path, int depth, TypeName leafType) {
        String binding = "_m" + (depth + 1);
        String key = path.get(depth);
        boolean isLeaf = depth == path.size() - 1;
        if (isLeaf) {
            // leafType == null means "do not cast the Map.get result" -- the consumer applies its
            // own runtime guard (used by the NodeIdDecodeKeys leaf path).
            if (leafType == null) {
                return CodeBlock.of("$L instanceof $T<?, ?> $L ? $L.get($S) : null",
                    currentExpr, Map.class, binding, binding, key);
            }
            return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($T) $L.get($S) : null",
                currentExpr, Map.class, binding, leafType, binding, key);
        }
        CodeBlock next = CodeBlock.of("$L.get($S)", binding, key);
        return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($L) : null",
            currentExpr, Map.class, binding, buildMapChain(next, path, depth + 1, leafType));
    }

    private static String rawComponent(String typeName) {
        int lt = typeName.indexOf('<');
        return lt < 0 ? typeName : typeName.substring(0, lt);
    }
}
