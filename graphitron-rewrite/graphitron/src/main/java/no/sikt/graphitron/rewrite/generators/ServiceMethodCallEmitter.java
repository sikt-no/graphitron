package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ArgPath;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.model.ValueShape;

import java.util.ArrayList;
import java.util.List;

/**
 * R238 emitter: turns a {@link ServiceMethodCall} carrier into the ordered statement list for
 * a service fetcher's lambda body. The caller (today's
 * {@link TypeFetcherGenerator#buildServiceFetcherCommon(TypeFetcherEmissionContext, String,
 * no.sikt.graphitron.rewrite.model.MethodRef, String, TypeName,
 * java.util.Optional, String)}, post-cutover) appends the returned statements verbatim and
 * wraps them in the existing try/catch + ErrorRouter discipline.
 *
 * <h3>Returned statements</h3>
 *
 * <ol>
 *   <li>DSL prelude (when needed): {@code DSLContext dsl = graphitronContext(env).getDslContext(env);}</li>
 *   <li>Per-entry var-decls in {@code ctorArgs} order (when the carrier is {@link ServiceMethodCall.Instance})
 *       followed by {@code methodArgs}. Each non-DSL entry contributes one var-decl whose RHS is
 *       its {@link MappingEntry} arm's expression form.</li>
 *   <li>Final result assignment:
 *     <ul>
 *       <li>{@link ServiceMethodCall.Static}: {@code ReturnType result = ClassName.methodName(methodArgs);}</li>
 *       <li>{@link ServiceMethodCall.Instance}: {@code ReturnType result = new ClassName(ctorArgs).methodName(methodArgs);}</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Each entry's call-site identifier reads from {@link MappingEntry.FromArg#javaName()} /
 * {@link MappingEntry.FromContext#javaName()}; {@link MappingEntry.FromDsl} positions read the
 * shared {@code dsl} local.
 */
public final class ServiceMethodCallEmitter {

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");

    /**
     * Emit the body statements for a {@link ServiceMethodCall}. {@code outputPackage} is used
     * to resolve the {@code GraphitronContext} reference at the call to
     * {@code graphitronContext(env).getDslContext(env)} / {@code .getContextArgument(env, key)}.
     */
    public static List<CodeBlock> emit(ServiceMethodCall call, String outputPackage) {
        ClassName graphitronContext = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        List<CodeBlock> out = new ArrayList<>();

        boolean needsDsl = anyFromDsl(allEntries(call)) || call instanceof ServiceMethodCall.Instance;
        if (needsDsl) {
            out.add(CodeBlock.of("$T dsl = $T.graphitronContext(env).getDslContext(env)",
                DSL_CONTEXT, graphitronContext));
        }

        if (call instanceof ServiceMethodCall.Instance inst) {
            for (MappingEntry e : inst.ctorArgs()) {
                addVarDecl(out, e, graphitronContext);
            }
        }
        for (MappingEntry e : call.methodArgs()) {
            addVarDecl(out, e, graphitronContext);
        }

        out.add(finalAssignment(call));
        return out;
    }

    private static List<MappingEntry> allEntries(ServiceMethodCall call) {
        if (call instanceof ServiceMethodCall.Instance inst) {
            List<MappingEntry> all = new ArrayList<>(inst.ctorArgs().size() + inst.methodArgs().size());
            all.addAll(inst.ctorArgs());
            all.addAll(inst.methodArgs());
            return all;
        }
        return call.methodArgs();
    }

    private static boolean anyFromDsl(List<MappingEntry> entries) {
        for (MappingEntry e : entries) {
            if (e instanceof MappingEntry.FromDsl) return true;
        }
        return false;
    }

    private static void addVarDecl(List<CodeBlock> out, MappingEntry entry, ClassName graphitronContext) {
        switch (entry) {
            case MappingEntry.FromDsl ignored -> { /* shares the prelude's dsl local */ }
            case MappingEntry.FromContext ctx ->
                out.add(CodeBlock.of("$T $L = ($T) $T.graphitronContext(env).getContextArgument(env, $S)",
                    ctx.javaType(), ctx.javaName(), ctx.javaType(), graphitronContext, ctx.contextKey()));
            case MappingEntry.FromArg arg -> {
                CodeBlock expr = valueShapeExpression(arg.shape());
                out.add(CodeBlock.of("$T $L = $L", arg.shape().javaType(), arg.javaName(), expr));
            }
        }
    }

    /**
     * Render an expression that evaluates to the value at the given {@link ValueShape}. Scalar
     * leaves inline directly; composite shapes ({@link ValueShape.RecordInput},
     * {@link ValueShape.JavaBeanInput}, {@link ValueShape.ListOf}) delegate to a
     * {@code private static} helper on the enclosing fetcher class. R238 lands the carrier and
     * the call-site expression form; helper emission (creating the
     * {@code private static T create<Bean>(Map) }-style helpers) reuses the existing
     * {@code InputBeanInstantiationEmitter} machinery and is wired in at cutover.
     */
    static CodeBlock valueShapeExpression(ValueShape shape) {
        return switch (shape) {
            case ValueShape.Scalar s -> scalarExpression(s);
            case ValueShape.ListOf list -> listExpression(list);
            case ValueShape.RecordInput rec -> compositeHelperCall(rec.javaClass(), rec.fields(), pathFor(shape));
            case ValueShape.JavaBeanInput bean -> compositeHelperCall(bean.javaClass(), bean.fields(), pathFor(shape));
        };
    }

    private static CodeBlock scalarExpression(ValueShape.Scalar scalar) {
        ArgPath path = scalar.sdlPath();
        if (path.deeperSegments().isEmpty()) {
            return scalarLeaf(scalar.leafTransform(), scalar.javaType(),
                CodeBlock.of("env.getArgument($S)", path.outerArgName()));
        }
        // Multi-segment path: null-safe traversal from the outer-arg Map through nested keys.
        return mapTraversal(scalar.leafTransform(), scalar.javaType(), path);
    }

    private static CodeBlock scalarLeaf(CallSiteExtraction leaf, TypeName javaType, CodeBlock rawValue) {
        return switch (leaf) {
            case CallSiteExtraction.Direct ignored -> CodeBlock.of("($T) $L", javaType, rawValue);
            case CallSiteExtraction.EnumValueOf ev -> CodeBlock.of(
                "$L == null ? null : $T.valueOf(($T) $L)",
                rawValue, ClassName.bestGuess(ev.enumClassName()), ClassName.get(String.class), rawValue);
            case CallSiteExtraction.JooqConvert jc -> CodeBlock.of("($T) $L", javaType, rawValue);
            case CallSiteExtraction.NodeIdDecodeKeys nid -> CodeBlock.of("($T) $L", javaType, rawValue);
            // Unreachable for well-formed Scalar leaves; defensive fallback.
            default -> CodeBlock.of("($T) $L", javaType, rawValue);
        };
    }

    private static CodeBlock mapTraversal(CallSiteExtraction leaf, TypeName javaType, ArgPath path) {
        // outerArg -> chained Map.get descent; null-safe per spec for NestedInputField.
        CodeBlock.Builder traversal = CodeBlock.builder()
            .add("(($T<$T, $T>) env.getArgument($S))",
                ClassName.get(java.util.Map.class), ClassName.get(String.class), ClassName.OBJECT,
                path.outerArgName());
        for (String seg : path.deeperSegments()) {
            traversal.add(" instanceof $T m1$L ? (($T<$T, $T>) m1$L).get($S) : null",
                ClassName.get(java.util.Map.class), seg,
                ClassName.get(java.util.Map.class), ClassName.get(String.class), ClassName.OBJECT,
                seg, seg);
        }
        return scalarLeaf(leaf, javaType, traversal.build());
    }

    private static CodeBlock listExpression(ValueShape.ListOf list) {
        // R238 cutover wires element-wise mapping through extracted helpers; ship the carrier
        // shape now and have the helper-emitter fill this in at cutover.
        return CodeBlock.of("/* R238: list mapping for $L pending helper extraction */ null",
            list.sdlPath().outerArgName());
    }

    private static CodeBlock compositeHelperCall(ClassName beanClass, List<ValueShape.FieldBinding> fields, ArgPath path) {
        // R238 cutover wires per-bean static helpers (create<Bean>(Map)); the carrier ships now
        // and the helper plumbing follows. Emit a structural placeholder so the carrier's
        // intent is visible in unit-tier emitter snapshots without committing to a final form.
        return CodeBlock.of("/* R238: bean construct $T from $L pending helper extraction */ null",
            beanClass, path.outerArgName());
    }

    private static ArgPath pathFor(ValueShape shape) {
        return switch (shape) {
            case ValueShape.Scalar s -> s.sdlPath();
            case ValueShape.ListOf l -> l.sdlPath();
            case ValueShape.RecordInput r -> r.fields().isEmpty()
                ? ArgPath.head("")
                : firstLeafPath(r.fields().getFirst().shape());
            case ValueShape.JavaBeanInput b -> b.fields().isEmpty()
                ? ArgPath.head("")
                : firstLeafPath(b.fields().getFirst().shape());
        };
    }

    private static ArgPath firstLeafPath(ValueShape shape) {
        return switch (shape) {
            case ValueShape.Scalar s -> s.sdlPath();
            case ValueShape.ListOf l -> l.sdlPath();
            case ValueShape.RecordInput r -> r.fields().isEmpty()
                ? ArgPath.head("")
                : firstLeafPath(r.fields().getFirst().shape());
            case ValueShape.JavaBeanInput b -> b.fields().isEmpty()
                ? ArgPath.head("")
                : firstLeafPath(b.fields().getFirst().shape());
        };
    }

    private static CodeBlock finalAssignment(ServiceMethodCall call) {
        CodeBlock argList = argList(call.methodArgs());
        return switch (call) {
            case ServiceMethodCall.Static s -> CodeBlock.of("$T result = $T.$L($L)",
                s.javaReturnType(), ClassName.bestGuess(s.fqClassName()), s.methodName(), argList);
            case ServiceMethodCall.Instance i -> CodeBlock.of("$T result = new $T($L).$L($L)",
                i.javaReturnType(), ClassName.bestGuess(i.fqClassName()),
                argList(i.ctorArgs()), i.methodName(), argList);
        };
    }

    private static CodeBlock argList(List<MappingEntry> entries) {
        if (entries.isEmpty()) return CodeBlock.of("");
        CodeBlock.Builder b = CodeBlock.builder();
        boolean first = true;
        for (MappingEntry e : entries) {
            if (!first) b.add(", ");
            first = false;
            String name = switch (e) {
                case MappingEntry.FromDsl ignored -> "dsl";
                case MappingEntry.FromContext c -> c.javaName();
                case MappingEntry.FromArg a -> a.javaName();
            };
            b.add("$L", name);
        }
        return b.build();
    }

    private ServiceMethodCallEmitter() {}
}
