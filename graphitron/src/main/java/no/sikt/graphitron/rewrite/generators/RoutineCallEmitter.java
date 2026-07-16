package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.RoutineRef;
import no.sikt.graphitron.rewrite.model.TableExpr;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;

/**
 * Emits the table expression for a {@link TableExpr.RoutineCall} node: the schema's
 * generated {@code Routines} convenience method invoked with the bound IN parameters,
 * {@code Routines.<method>(<args>)}. Callers append {@code .as(alias)} like any table.
 *
 * <p>The call surface forks on correlation, decided once from the bindings:
 *
 * <ul>
 *   <li><b>Uncorrelated</b> (every binding is {@link ParamSource.Arg}): the <em>value</em>
 *       overload, with each argument read as a typed value. jOOQ's generated value overload
 *       binds each argument through the parameter's {@code DataType}
 *       ({@code DSL.val(v, SQLDataType.X)} inside the generated method), so the typed binding
 *       comes for free — the same shape the root {@code @routine} fetcher emits.</li>
 *   <li><b>Correlated</b> (any binding is {@link ParamSource.SourceColumn}): the
 *       <em>Field</em> overload, selected by javac overload resolution because every argument
 *       is a jOOQ {@code Field} — the previous node's aliased column for column-sourced
 *       bindings, {@code DSL.val(<typed read>)} for value-sourced ones. jOOQ's TVF codegen
 *       exposes no {@code Parameter} constants, so the correlated value-arg binding rides the
 *       Java-typed read rather than a two-arg {@code DSL.val(v, dataType)}; this shares the
 *       enum/ID-as-String coercion residue with the root slice.</li>
 * </ul>
 */
public final class RoutineCallEmitter {

    private RoutineCallEmitter() {}

    /**
     * Emits {@code Routines.<method>(<args>)} for the routine node.
     *
     * @param rc           the routine-call target node
     * @param previousNode where {@link ParamSource.SourceColumn} bindings read the previous
     *                     chain node's columns — a typed alias in scope, or the batched form's
     *                     {@code parentInput} field lookup (the {@link PreviousNodeRef} fork)
     * @param argSource    where {@link ParamSource.Arg} bindings read their runtime
     *                     values (the env-vs-SelectedField fork)
     */
    public static CodeBlock emitCall(TableExpr.RoutineCall rc, PreviousNodeRef previousNode,
            ArgumentValueSource argSource) {
        var routine = rc.routine();
        boolean correlated = routine.argBindings().stream()
            .anyMatch(b -> b.source() instanceof ParamSource.SourceColumn);
        CodeBlock args = CodeBlock.join(routine.argBindings().stream()
            .map(b -> argExpression(b, correlated, previousNode, argSource))
            .toList(), ", ");
        return CodeBlock.of("$T.$L($L)", routine.routinesClass(), routine.methodName(), args);
    }

    private static CodeBlock argExpression(RoutineRef.ArgBinding b, boolean correlated,
            PreviousNodeRef previousNode, ArgumentValueSource argSource) {
        return switch (b.source()) {
            case ParamSource.Arg arg -> {
                CodeBlock raw = switch (argSource) {
                    case ArgumentValueSource.Env ignored ->
                        CodeBlock.of("env.<$T>getArgument($S)", b.paramType(), arg.graphqlArgName());
                    case ArgumentValueSource.FromSelectedField sf ->
                        CodeBlock.of("($T) $L.getArguments().get($S)", b.paramType(), sf.sfLocal(), arg.graphqlArgName());
                };
                yield correlated ? CodeBlock.of("$T.val($L)", DSL, raw) : raw;
            }
            case ParamSource.SourceColumn sc -> switch (previousNode) {
                case PreviousNodeRef.TypedAlias ta ->
                    CodeBlock.of("$L.$L", ta.alias(), sc.column().javaName());
                case PreviousNodeRef.ParentInputField pif ->
                    CodeBlock.of("$L.field($S, $T.$L.$L.getDataType())",
                        pif.valuesLocal(), sc.column().sqlName(),
                        pif.ownerTable().constantsClass(), pif.ownerTable().javaFieldName(),
                        sc.column().javaName());
                // Classifier-unreachable: a SourceColumn binding reads the previous
                // chain node's column, but a None head has no previous node. The root chain pins
                // every start binding to ParamSource.Arg (QueryRoutineTableField's compact
                // constructor; RoutineDirectiveResolver rejects columnMapping at root), so this
                // combination cannot be produced.
                case PreviousNodeRef.None ignored -> throw new IllegalStateException(
                    "correlated column binding for parameter '" + b.routineParamName()
                    + "' reached a headless (None) routine call — a root chain's head has no "
                    + "previous node, and QueryRoutineTableField pins every start binding to "
                    + "ParamSource.Arg");
            };
            case ParamSource.Context ignored -> throw nonRoutineParamSource(b);
            case ParamSource.Sources ignored -> throw nonRoutineParamSource(b);
            case ParamSource.DslContext ignored -> throw nonRoutineParamSource(b);
            case ParamSource.Table ignored -> throw nonRoutineParamSource(b);
            case ParamSource.SourceTable ignored -> throw nonRoutineParamSource(b);
        };
    }

    /**
     * A routine {@link RoutineRef.ArgBinding} carrying a {@link ParamSource} arm
     * {@code RoutineDirectiveResolver} never mints for routine bindings reached emission —
     * a classifier bug, not an authoring error.
     */
    private static IllegalStateException nonRoutineParamSource(RoutineRef.ArgBinding binding) {
        return new IllegalStateException(
            "routine binding for parameter '" + binding.routineParamName() + "' carries "
            + binding.source().getClass().getSimpleName()
            + " — RoutineDirectiveResolver mints only ParamSource.Arg and ParamSource.SourceColumn");
    }
}
