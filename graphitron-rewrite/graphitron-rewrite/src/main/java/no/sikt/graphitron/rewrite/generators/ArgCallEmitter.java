package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

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
                buildNestedInputFieldExtraction(nif.outerArgName(), nif.path(), param.typeName());
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
    private static CodeBlock buildNestedInputFieldExtraction(String outerArgName, List<String> path, String leafTypeName) {
        TypeName leafType = ClassName.bestGuess(rawComponent(leafTypeName));
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
