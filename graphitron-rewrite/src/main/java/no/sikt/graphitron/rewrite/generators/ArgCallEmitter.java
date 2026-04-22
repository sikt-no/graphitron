package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import java.util.List;

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
        };
    }
}
