package no.sikt.graphitron.render;

import no.sikt.graphitron.command.RoutineCall;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.PathExpr;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.RoutineRef;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;

/**
 * Emits the table expression for a {@link TableExpr.RoutineCall} node: the schema's
 * generated {@code Routines} convenience method invoked with the bound IN parameters,
 * {@code Routines.<method>(<args>)}. Callers append {@code .as(alias)} like any table.
 *
 * <p>A parameter bound to an {@code argMapping} path whose last segment names a key column of a
 * {@code @nodeId}'s node type reads that column off a decoded record instead of off the wire map;
 * {@link ProjectedKeyReads} holds those, and the fork is row presence rather than a shape test, a
 * projected path being indistinguishable from an ordinary dotted one without the store's resolution.
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

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

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
     * @param argHelpers   collects the descent helper a dot-path binding needs; untouched when
     *                     every binding names a bare slot
     * @param keys         the emitting method's node-id decodes: a binding whose path projects a
     *                     key column reads it off a decoded record from here, and the caller emits
     *                     {@link ProjectedKeyReads#declarations()} ahead of the statement holding
     *                     this expression
     */
    public static CodeBlock emitCall(TableExpr.RoutineCall rc, PreviousNodeRef previousNode,
            ArgumentValueSource argSource, ArgPathHelperRegistry argHelpers,
            ProjectedKeyReads keys) {
        var routine = rc.routine();
        boolean correlated = routine.argBindings().stream()
            .anyMatch(b -> b.source() instanceof ParamSource.SourceColumn);
        CodeBlock args = CodeBlock.join(routine.argBindings().stream()
            .map(b -> argExpression(b, correlated, previousNode, argSource, argHelpers, keys))
            .toList(), ", ");
        return CodeBlock.of("$T.$L($L)", routine.routinesClass(), routine.methodName(), args);
    }

    /**
     * {@code Routines.<method>(<args>)} for a caller whose call arrived as a command row.
     *
     * <p>Uncorrelated by construction, so the value overload is the only shape: a command-tier
     * {@link RoutineCall} binds every parameter to a request value, there being no previous chain
     * node at a mutation root for a column binding to name. That is the row's narrowing rather
     * than an assumption made here, which is why this method takes no
     * {@link PreviousNodeRef} to be given {@link PreviousNodeRef.None}.
     *
     * @param call       the routine call to emit
     * @param argSource  where the argument values are read, the env-vs-SelectedField fork
     * @param argHelpers collects the descent helper a dotted binding needs
     * @param keys       the emitting method's node-id decodes, for a binding whose path projects a
     *                   key column
     */
    public static CodeBlock emitCall(RoutineCall call, ArgumentValueSource argSource,
            ArgPathHelperRegistry argHelpers, ProjectedKeyReads keys) {
        CodeBlock args = CodeBlock.join(call.arguments().stream()
            .map(a -> argExpression(a, argSource, argHelpers, keys))
            .toList(), ", ");
        return CodeBlock.of("$T.$L($L)", CatalogRefs.className(call.routinesClassName()),
            call.methodName(), args);
    }

    /**
     * One argument of a command-tier call, read off the request at the path the row states.
     *
     * <p>The path arrives written rather than resolved, so the segment split happens here. That is
     * the whole of what the resolved carrier was supplying: both reads below take segment names,
     * and the per-segment list-lifting the carrier also holds is consulted by neither.
     */
    private static CodeBlock argExpression(RoutineCall.RoutineArgument argument,
            ArgumentValueSource argSource, ArgPathHelperRegistry argHelpers, ProjectedKeyReads keys) {
        var segments = argument.segments();
        TypeName paramType = CatalogRefs.typeName(argument.javaTypeName());
        // Row presence decides, not a shape test on the path, for the reason the model-shaped arm
        // below states: a projected binding's path is spelled exactly like an ordinary dotted one.
        // Asked of a bare path too: a binding that stops on a @nodeId whose key is one column is a
        // projection, and declining single-segment paths here is what used to hand such a parameter
        // the base64 wire string. Which segment the node id is comes off the row, inside the sink.
        return keys.readFor(segments,
                leaf -> descentRead(leaf, TypeName.get(Object.class), argSource, argHelpers))
            .orElseGet(() -> segments.size() == 1
                ? typedSlotRead(paramType, segments.getFirst(), argSource)
                : descentRead(segments, paramType, argSource, argHelpers));
    }

    /**
     * A path's value read off the request: the outer slot directly when the path is one segment,
     * otherwise through a registered descent helper that walks the tail and applies the leaf cast.
     * Untyped at the read where the caller asks for {@code Object}, which is what the wire read
     * behind a projected binding wants: the decode helper guards the wire shape itself, so a value
     * that is not a string is a null decode rather than a cast failure.
     */
    private static CodeBlock descentRead(List<String> segments, TypeName leafType,
            ArgumentValueSource argSource, ArgPathHelperRegistry argHelpers) {
        String head = segments.getFirst();
        CodeBlock root = switch (argSource) {
            case ArgumentValueSource.Env ignored -> CodeBlock.of("env.getArgument($S)", head);
            case ArgumentValueSource.FromSelectedField sf ->
                CodeBlock.of("$L.getArguments().get($S)", sf.sfLocal(), head);
        };
        if (segments.size() == 1) {
            return root;
        }
        return CodeBlock.of("$L($L)",
            argHelpers.register(head, segments.subList(1, segments.size()), leafType), root);
    }

    private static CodeBlock argExpression(RoutineRef.ArgBinding b, boolean correlated,
            PreviousNodeRef previousNode, ArgumentValueSource argSource,
            ArgPathHelperRegistry argHelpers, ProjectedKeyReads keys) {
        return switch (b.source()) {
            case ParamSource.Arg arg -> {
                // Row presence decides, not a shape test on the path: a projected binding's path
                // looks exactly like an ordinary dotted one, the difference being which of its
                // segments the @nodeId is, which is the store's resolution and the sink's to apply.
                // The whole-slot install rail is asked first: where it owns the binding this sink
                // stands aside, which is the precedence ProjectedKeyReads.installRailOwns states.
                var projected = ProjectedKeyReads.installRailOwns(arg.extraction())
                    ? java.util.Optional.<CodeBlock>empty()
                    : keys.readFor(segmentNames(arg.path()),
                        leaf -> descentRead(leaf, TypeName.get(Object.class), argSource, argHelpers));
                CodeBlock raw = projected
                    .orElseGet(() -> arg.path().isHead()
                        ? typedSlotRead(b.paramType(), arg.path().headName(), argSource)
                        : nestedSlotRead(b.paramType(), arg.path(), argSource, argHelpers));
                yield correlated ? CodeBlock.of("$T.val($L)", DSL, raw) : raw;
            }
            case ParamSource.SourceColumn sc -> switch (previousNode) {
                case PreviousNodeRef.TypedAlias ta ->
                    CodeBlock.of("$L.$L", ta.alias(), sc.column().javaName());
                case PreviousNodeRef.ParentInputField pif ->
                    CodeBlock.of("$L.field($S, $T.$L.$L.getDataType())",
                        pif.valuesLocal(), sc.column().sqlName(),
                        CatalogRefs.constantsClass(pif.ownerTable()), pif.ownerTable().javaFieldName(),
                        sc.column().javaName());
                // Classifier-unreachable: a SourceColumn binding reads the previous
                // chain node's column, but a None head has no previous node. The root chain pins
                // every start binding to ParamSource.Arg (RoutineChain's compact
                // constructor; RoutineDirectiveResolver rejects columnMapping at root), so this
                // combination cannot be produced.
                case PreviousNodeRef.None ignored -> throw new IllegalStateException(
                    "correlated column binding for parameter '" + b.routineParamName()
                    + "' reached a headless (None) routine call — a root chain's head has no "
                    + "previous node, and RoutineChain pins every start binding to "
                    + "ParamSource.Arg");
            };
        };
    }

    /** A resolved path's segment names, outermost first: the {@code argMapping} as written. */
    private static List<String> segmentNames(PathExpr path) {
        return path.segments().stream().map(PathExpr.Segment::name).toList();
    }

    /** The bare-slot read: the argument value typed at the read itself. */
    private static CodeBlock typedSlotRead(TypeName paramType, String slot, ArgumentValueSource argSource) {
        return switch (argSource) {
            case ArgumentValueSource.Env ignored ->
                CodeBlock.of("env.<$T>getArgument($S)", paramType, slot);
            case ArgumentValueSource.FromSelectedField sf ->
                CodeBlock.of("($T) $L.getArguments().get($S)", paramType, sf.sfLocal(), slot);
        };
    }

    /**
     * The dot-path read: the outer slot's raw value handed to a registered descent helper, which
     * walks the tail and applies the leaf cast. Untyped at the read because the helper's parameter
     * is {@code Object} and the walk has to guard each level anyway.
     */
    private static CodeBlock nestedSlotRead(TypeName paramType, PathExpr path,
            ArgumentValueSource argSource, ArgPathHelperRegistry argHelpers) {
        var segments = path.segments();
        var tail = segments.subList(1, segments.size()).stream().map(PathExpr.Segment::name).toList();
        String helper = argHelpers.register(path.headName(), tail, paramType);
        CodeBlock root = switch (argSource) {
            case ArgumentValueSource.Env ignored ->
                CodeBlock.of("env.getArgument($S)", path.headName());
            case ArgumentValueSource.FromSelectedField sf ->
                CodeBlock.of("$L.getArguments().get($S)", sf.sfLocal(), path.headName());
        };
        return CodeBlock.of("$L($L)", helper, root);
    }
}
