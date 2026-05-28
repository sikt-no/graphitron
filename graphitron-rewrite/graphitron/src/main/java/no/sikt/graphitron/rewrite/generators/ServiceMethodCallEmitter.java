package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
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
     * Emit the body statements for a {@link ServiceMethodCall}. The {@code result} local is
     * declared with the carrier's {@link ServiceMethodCall#javaReturnType()}. The
     * {@code outputPackage} parameter is reserved for resolving package-qualified references in
     * future extensions; the current emitter only generates unqualified same-class calls into
     * the {@code graphitronContext(env)} private static helper that
     * {@code TypeFetcherGenerator.buildGraphitronContextHelper} emits on every {@code *Fetchers}
     * class. The caller is responsible for registering
     * {@link TypeFetcherEmissionContext.HelperKind#GRAPHITRON_CONTEXT} so the helper is actually
     * emitted (the existing {@link TypeFetcherEmissionContext#graphitronContextCall()} accessor
     * records the dependency on the way out).
     */
    public static List<CodeBlock> emit(ServiceMethodCall call, String outputPackage) {
        return emit(call, outputPackage, call.javaReturnType());
    }

    /**
     * Variant that declares the {@code result} local with an explicit {@code resultLocalType}
     * instead of the carrier's reflected return type. Used by the root-fetcher emitter, which
     * sometimes declares the local with the GraphQL-side classification (e.g. a table record
     * class) rather than the dev method's reflected return; the actual method return value is
     * shape-compatible by classifier-time validation.
     */
    @SuppressWarnings("unused")
    public static List<CodeBlock> emit(ServiceMethodCall call, String outputPackage, TypeName resultLocalType) {
        List<CodeBlock> out = new ArrayList<>();

        boolean needsDsl = anyFromDsl(allEntries(call)) || call instanceof ServiceMethodCall.Instance;
        if (needsDsl) {
            out.add(CodeBlock.of("$T dsl = graphitronContext(env).getDslContext(env)", DSL_CONTEXT));
        }

        if (call instanceof ServiceMethodCall.Instance inst) {
            for (MappingEntry e : inst.ctorArgs()) {
                addVarDecl(out, e);
            }
        }
        for (MappingEntry e : call.methodArgs()) {
            addVarDecl(out, e);
        }

        out.add(finalAssignment(call, resultLocalType));
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

    private static void addVarDecl(List<CodeBlock> out, MappingEntry entry) {
        switch (entry) {
            case MappingEntry.FromDsl ignored -> { /* shares the prelude's dsl local */ }
            case MappingEntry.FromContext ctx ->
                out.add(CodeBlock.of("$T $L = ($T) graphitronContext(env).getContextArgument(env, $S)",
                    ctx.javaType(), ctx.javaName(), ctx.javaType(), ctx.contextKey()));
            case MappingEntry.FromArg arg -> {
                CodeBlock expr = valueShapeExpression(arg.shape());
                out.add(CodeBlock.of("$T $L = $L", arg.shape().javaType(), arg.javaName(), expr));
            }
        }
    }

    /**
     * Render an expression that evaluates to the value at the given {@link ValueShape}. Scalar
     * leaves inline directly; composite shapes ({@link ValueShape.RecordInput},
     * {@link ValueShape.JavaBeanInput}, {@link ValueShape.ListOf}) delegate to the
     * {@code create<Bean>} / {@code create<Bean>List} helpers emitted on the enclosing
     * {@code *Fetchers} class by {@link InputBeanInstantiationEmitter}. The helper names follow
     * the R150 convention; the helper queue in {@link TypeFetcherGenerator} is driven from the
     * call sites that produce {@link CallSiteExtraction.InputBean} arms (today the four service
     * permits implement both {@code ServiceField} and {@code MethodBackedField} during the
     * additive cutover, so the queue still sees them via the legacy {@code method().callParams()}
     * walk).
     */
    static CodeBlock valueShapeExpression(ValueShape shape) {
        return switch (shape) {
            case ValueShape.Scalar s -> scalarExpression(s);
            case ValueShape.ListOf list -> listExpression(list);
            case ValueShape.RecordInput rec -> compositeHelperCall(rec.javaClass(), rec.fields());
            case ValueShape.JavaBeanInput bean -> compositeHelperCall(bean.javaClass(), bean.fields());
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

    /**
     * Null-safe nested {@code Map<String, Object>} traversal from a top-level
     * {@code env.getArgument(outer)} through each segment of {@code path.deeperSegments()}, with
     * the leaf transform applied to the final {@code Map.get(leafSegment)} value. Each step
     * uses an {@code instanceof Map<?, ?> _mN} rebind (wildcard-parameterised, conditional
     * under {@code -source 17}) so a {@code null} anywhere in the chain yields {@code null}
     * rather than a {@link NullPointerException} or a {@link ClassCastException}.
     *
     * <p>Mirrors {@code ArgCallEmitter#buildMapChain}; the older site stays in place for non-
     * R238 callsites (condition, tableMethod, externalField) until those slices migrate.
     */
    private static CodeBlock mapTraversal(CallSiteExtraction leaf, TypeName javaType, ArgPath path) {
        CodeBlock root = CodeBlock.of("env.getArgument($S)", path.outerArgName());
        return buildMapChain(root, path.deeperSegments(), 0, leaf, javaType);
    }

    private static CodeBlock buildMapChain(CodeBlock currentExpr, List<String> segments, int depth,
                                            CallSiteExtraction leaf, TypeName javaType) {
        String key = segments.get(depth);
        boolean isLeaf = depth == segments.size() - 1;
        String binding = "_m" + (depth + 1);
        ClassName mapClass = ClassName.get(java.util.Map.class);

        if (isLeaf) {
            CodeBlock leafExpr = scalarLeaf(leaf, javaType,
                CodeBlock.of("$L.get($S)", binding, key));
            return CodeBlock.of("$L instanceof $T<?, ?> $L ? $L : null",
                currentExpr, mapClass, binding, leafExpr);
        }
        CodeBlock next = CodeBlock.of("$L.get($S)", binding, key);
        return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($L) : null",
            currentExpr, mapClass, binding,
            buildMapChain(next, segments, depth + 1, leaf, javaType));
    }

    private static CodeBlock listExpression(ValueShape.ListOf list) {
        ValueShape elt = list.elementShape();
        String outerArg = list.sdlPath().outerArgName();
        return switch (elt) {
            case ValueShape.RecordInput rec ->
                CodeBlock.of("$L(env.getArgument($S))",
                    pluralHelperName(rec.javaClass()), outerArg);
            case ValueShape.JavaBeanInput bean ->
                CodeBlock.of("$L(env.getArgument($S))",
                    pluralHelperName(bean.javaClass()), outerArg);
            case ValueShape.Scalar s ->
                // List of scalars at a top-level path: the walker produces a Scalar directly
                // for {@code List<X>} args, so this arm is defensive. Cast the raw List<?> to
                // the declared element type via the leaf transform-equivalent expression.
                CodeBlock.of("($T) env.getArgument($S)",
                    ParameterizedTypeName.get(ClassName.get(List.class), s.javaType()),
                    outerArg);
            case ValueShape.ListOf nested ->
                // Nested ListOf (list of lists) is not produced by the current walker. Defensive
                // fallback so the switch is exhaustive without inventing a syntax.
                CodeBlock.of("/* unsupported nested ListOf at $L */ null", outerArg);
        };
    }

    /**
     * Call the {@code create<Bean>} singular helper emitted on the enclosing {@code *Fetchers}
     * class. The walker positions every {@link ValueShape.RecordInput} / {@link ValueShape.JavaBeanInput}
     * at a top-level path (the bean is itself the SDL arg; sibling field paths share the outer
     * arg name as a prefix), so the call site is {@code createBean(env.getArgument("outerArg"))}.
     * The outer arg name is derived from any sibling field's path; the {@code @Nullable}
     * graphql-java argument map is passed through unchanged and the helper handles null.
     */
    private static CodeBlock compositeHelperCall(ClassName beanClass, List<ValueShape.FieldBinding> fields) {
        return CodeBlock.of("$L(env.getArgument($S))",
            singularHelperName(beanClass), outerArgOf(fields));
    }

    private static String singularHelperName(ClassName beanClass) {
        return "create" + beanClass.simpleName();
    }

    private static String pluralHelperName(ClassName beanClass) {
        return "create" + beanClass.simpleName() + "List";
    }

    /**
     * Derive the outer SDL arg name shared by every leaf under a composite. Every
     * {@link ValueShape.FieldBinding} inside a bean carries the outer arg in its leaf path's
     * head (the walker calls {@code path.append(...)} per field while keeping the same
     * {@code outerArgName}). An empty bean is structurally well-formed but has no source —
     * defensively returns the empty string; the only way to reach this branch is a bean class
     * with no SDL-visible fields, which never produces a useful helper call anyway.
     */
    private static String outerArgOf(List<ValueShape.FieldBinding> fields) {
        if (fields.isEmpty()) return "";
        return outerArgOf(fields.getFirst().shape());
    }

    private static String outerArgOf(ValueShape shape) {
        return switch (shape) {
            case ValueShape.Scalar s -> s.sdlPath().outerArgName();
            case ValueShape.ListOf l -> l.sdlPath().outerArgName();
            case ValueShape.RecordInput r -> outerArgOf(r.fields());
            case ValueShape.JavaBeanInput b -> outerArgOf(b.fields());
        };
    }

    private static CodeBlock finalAssignment(ServiceMethodCall call, TypeName resultLocalType) {
        CodeBlock argList = argList(call.methodArgs());
        return switch (call) {
            case ServiceMethodCall.Static s -> CodeBlock.of("$T result = $T.$L($L)",
                resultLocalType, ClassName.bestGuess(s.fqClassName()), s.methodName(), argList);
            case ServiceMethodCall.Instance i -> CodeBlock.of("$T result = new $T($L).$L($L)",
                resultLocalType, ClassName.bestGuess(i.fqClassName()),
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
