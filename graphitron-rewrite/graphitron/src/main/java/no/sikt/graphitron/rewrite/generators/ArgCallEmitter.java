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
    public static CodeBlock buildCallArgs(List<CallParam> params, String conditionsClassName, String srcAlias) {
        var args = CodeBlock.builder();
        args.add("$L", srcAlias);
        for (var param : params) {
            args.add(", $L", buildArgExtraction(param, conditionsClassName, srcAlias));
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
    public static CodeBlock buildMethodBackedCallArgs(MethodRef method, CodeBlock tableExpression, String conditionsClassName) {
        var args = CodeBlock.builder();
        boolean first = true;
        for (var param : method.params()) {
            if (!first) args.add(", ");
            first = false;
            args.add(emitForParam(param, tableExpression, conditionsClassName));
        }
        return args.build();
    }

    private static CodeBlock emitForParam(MethodRef.Param param, CodeBlock tableExpression, String conditionsClassName) {
        var source = param.source();
        return switch (source) {
            case ParamSource.Arg arg -> buildArgExtraction(
                new CallParam(param.name(), arg.extraction(), false, param.typeName()),
                conditionsClassName,
                null);
            case ParamSource.Context ignored ->
                CodeBlock.of("graphitronContext(env).getContextArgument(env, $S)", param.name());
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
            case ParamSource.Sources ignored ->
                throw new IllegalStateException(
                    "ParamSource.Sources reached buildMethodBackedCallArgs at root — should have been rejected at classifier time (Invariants §2): param '"
                    + param.name() + "'");
            case ParamSource.SourceTable ignored ->
                throw new IllegalStateException(
                    "ParamSource.SourceTable reached buildMethodBackedCallArgs — SourceTable is a child-field concept, unreachable at root: param '"
                    + param.name() + "'");
        };
    }

    public static CodeBlock buildArgExtraction(CallParam param, String conditionsClassName, String srcAlias) {
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
                CodeBlock.of("graphitronContext(env).getContextArgument(env, $S)", param.name());
            case CallSiteExtraction.JooqConvert jc -> param.list()
                ? CodeBlock.of("$L.stream().map($L.$L.getDataType()::convert).toList()",
                    toCamelCase(param.name()) + "Keys", srcAlias, jc.columnJavaName())
                : CodeBlock.of("$L.$L.getDataType().convert((String) env.getArgument($S))",
                    srcAlias, jc.columnJavaName(), param.name());
            case CallSiteExtraction.NestedInputField nif ->
                buildNestedInputFieldExtraction(nif.outerArgName(), nif.path(), param.typeName(), param.list());
        };
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
    private static CodeBlock buildNestedInputFieldExtraction(String outerArgName, List<String> path, String leafTypeName, boolean list) {
        ClassName rawLeaf = ClassName.bestGuess(rawComponent(leafTypeName));
        TypeName leafType = list
            ? ParameterizedTypeName.get(ClassName.get(List.class), rawLeaf)
            : rawLeaf;
        CodeBlock root = CodeBlock.of("env.getArgument($S)", outerArgName);
        return buildMapChain(root, path, 0, leafType);
    }

    private static CodeBlock buildMapChain(CodeBlock currentExpr, List<String> path, int depth, TypeName leafType) {
        String binding = "_m" + (depth + 1);
        String key = path.get(depth);
        boolean isLeaf = depth == path.size() - 1;
        if (isLeaf) {
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
