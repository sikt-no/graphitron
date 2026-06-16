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
                // A nested-input arg of generic type extracts as `(List<X>) map.get(key)`, where the
                // map value is statically Object, so the cast is inherently unchecked (top-level args
                // ride `<T> T env.getArgument` inference and need no cast). Suppress at the narrowest
                // scope, this local declaration.
                if (castsUncheckedOffMap(arg.shape())) {
                    out.add(CodeBlock.of("@$T($S) $T $L = $L",
                        SuppressWarnings.class, "unchecked", arg.shape().javaType(), arg.javaName(), expr));
                } else {
                    out.add(CodeBlock.of("$T $L = $L", arg.shape().javaType(), arg.javaName(), expr));
                }
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
            case ValueShape.JooqRecordInput jr -> jooqRecordHelperCall(jr);
        };
    }

    /**
     * Call the {@code create<Record>} singular helper for a jOOQ {@code TableRecord} param (R311). A
     * {@link ValueShape.JooqRecordInput} carries its own {@code sdlPath}, so it reads the arg name
     * directly rather than recovering it from a {@code fields} list (the reason it does not reuse
     * {@link #compositeHelperCall}). The helper is named from the carrier's record class.
     */
    private static CodeBlock jooqRecordHelperCall(ValueShape.JooqRecordInput jr) {
        return CodeBlock.of("$L(env.getArgument($S))",
            singularHelperName(jr.carrier().table().recordClass()), jr.sdlPath().outerArgName());
    }

    /**
     * True when the arg's value expression casts a generic type off a {@code Map.get(...)} (a
     * multi-segment nested-input scalar of parameterised Java type). Such casts are inherently
     * unchecked; top-level args bind through {@code <T> T env.getArgument} inference and do not.
     */
    private static boolean castsUncheckedOffMap(ValueShape shape) {
        return shape instanceof ValueShape.Scalar s
            && !s.sdlPath().deeperSegments().isEmpty()
            && s.javaType() instanceof ParameterizedTypeName;
    }

    private static CodeBlock scalarExpression(ValueShape.Scalar scalar) {
        ArgPath path = scalar.sdlPath();
        if (path.deeperSegments().isEmpty()) {
            CodeBlock rawValue = CodeBlock.of("env.getArgument($S)", path.outerArgName());
            // Top-level arg off the <T> T env.getArgument: the typed local declared at the call
            // site (addVarDecl) drives inference, so no cast is needed and a cast to a generic
            // type (e.g. List<Integer>) would be an unchecked cast. EnumValueOf is the exception:
            // graphql-java delivers the enum as a String, so it still needs its valueOf coercion.
            return scalar.leafTransform() instanceof CallSiteExtraction.EnumValueOf
                ? scalarLeaf(scalar.leafTransform(), scalar.javaType(), rawValue)
                : rawValue;
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
     * Null-safe traversal from a top-level {@code env.getArgument(outer)} through each
     * {@link ArgPath.Segment} of {@code path.deeperSegments()}, with the leaf transform applied
     * to the final {@code Map.get(leafSegment)} value. Map-shaped segments rebind via
     * {@code instanceof Map<?, ?> mapN} (wildcard-parameterised, conditional under
     * {@code -source 17}); list-shaped segments stream through their elements via
     * {@code list.stream().map(elem -> ...).toList()}. {@code null} anywhere in the chain yields
     * {@code null} rather than an NPE or CCE.
     *
     * <p>The declared {@code javaType} carries one {@code List<>} wrap per {@code liftsList=true}
     * segment, including the leaf segment if it lifts. The emitter strips that many wraps to
     * find the innermost element type, then applies the leaf transform's cast against either
     * the stripped element type (non-list leaf) or its single-{@code List<>} re-wrap (list-typed
     * leaf). Mirrors {@code ArgCallEmitter#buildListAwarePathExtraction}; the older site stays
     * live for non-R238 callsites (condition, tableMethod, externalField) until those slices
     * migrate.
     */
    private static CodeBlock mapTraversal(CallSiteExtraction leaf, TypeName javaType, ArgPath path) {
        int liftCount = 0;
        for (ArgPath.Segment s : path.deeperSegments()) {
            if (s.liftsList()) liftCount++;
        }
        TypeName innerElementType = stripListWraps(javaType, liftCount);
        CodeBlock root = CodeBlock.of("env.getArgument($S)", path.outerArgName());
        return walkSegments(root, path.deeperSegments(), 0, leaf, innerElementType, new int[]{0});
    }

    /**
     * Strip {@code n} leading {@code List<...>} wraps from {@code type}. If the wrap count
     * exceeds the actual nesting (which indicates a classifier/schema drift), the helper stops
     * at the innermost {@code ParameterizedTypeName} found rather than throwing: the resulting
     * cast may fail at runtime, which surfaces the mismatch loudly enough.
     */
    private static TypeName stripListWraps(TypeName type, int n) {
        TypeName t = type;
        ClassName listRaw = ClassName.get(java.util.List.class);
        for (int i = 0; i < n; i++) {
            if (t instanceof ParameterizedTypeName ptn
                    && ptn.rawType().equals(listRaw)
                    && ptn.typeArguments().size() == 1) {
                t = ptn.typeArguments().getFirst();
            } else {
                return t;
            }
        }
        return t;
    }

    /**
     * Recursive emit for the segment walker. {@code currentExpr} is the {@code Object}-typed
     * value at the current depth; {@code segments[depth..]} are the remaining segments;
     * {@code innerElementType} is the leaf scalar's Java type after stripping all
     * {@code liftsList} wraps from the declared Java parameter type; {@code counter} is shared
     * across recursive calls so binding names {@code map1, list2, elem3, ...} stay distinct within
     * the same expression. At the leaf, if the segment itself lifts a list the cast target
     * wraps once in {@code List<innerElementType>}; otherwise the cast is the bare element type.
     */
    private static CodeBlock walkSegments(CodeBlock currentExpr, List<ArgPath.Segment> segments,
                                          int depth, CallSiteExtraction leaf,
                                          TypeName innerElementType, int[] counter) {
        ArgPath.Segment seg = segments.get(depth);
        boolean isLast = depth == segments.size() - 1;
        ClassName mapClass = ClassName.get(java.util.Map.class);
        ClassName listClass = ClassName.get(java.util.List.class);
        int mNum = ++counter[0];
        String mBind = "map" + mNum;

        if (isLast) {
            TypeName leafCastType = seg.liftsList()
                ? ParameterizedTypeName.get(listClass, innerElementType)
                : innerElementType;
            CodeBlock leafExpr = scalarLeaf(leaf, leafCastType,
                CodeBlock.of("$L.get($S)", mBind, seg.name()));
            return CodeBlock.of("$L instanceof $T<?, ?> $L ? $L : null",
                currentExpr, mapClass, mBind, leafExpr);
        }

        if (seg.liftsList()) {
            int lNum = ++counter[0];
            String lBind = "list" + lNum;
            int eNum = ++counter[0];
            String eBind = "elem" + eNum;
            CodeBlock recursed = walkSegments(CodeBlock.of("$L", eBind), segments, depth + 1,
                leaf, innerElementType, counter);
            return CodeBlock.of(
                "$L instanceof $T<?, ?> $L ? ($L.get($S) instanceof $T<?> $L "
                + "? $L.stream().map($L -> $L).toList() : null) : null",
                currentExpr, mapClass, mBind, mBind, seg.name(),
                listClass, lBind, lBind, eBind, recursed);
        }

        CodeBlock next = CodeBlock.of("$L.get($S)", mBind, seg.name());
        CodeBlock recursed = walkSegments(next, segments, depth + 1, leaf, innerElementType, counter);
        return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($L) : null",
            currentExpr, mapClass, mBind, recursed);
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
            case ValueShape.JooqRecordInput jr ->
                // R311: List<Record> param — the plural create<Record>List helper maps the singular
                // create<Record> over each element; the wire value for a [Input!] arg is a
                // List<Map<String, Object>>.
                CodeBlock.of("$L(env.getArgument($S))",
                    pluralHelperName(jr.carrier().table().recordClass()), outerArg);
            case ValueShape.Scalar ignored ->
                // List of scalars at a top-level path: the walker produces a Scalar directly
                // for {@code List<X>} args, so this arm is defensive. getArgument is <T> T, so the
                // typed call-site local / method parameter drives inference; no cast is needed
                // (and an explicit one to List<element> would be an unchecked cast).
                CodeBlock.of("env.getArgument($S)", outerArg);
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
            // R311: forced by the sealed addition but unreachable by construction — a JooqRecordInput is
            // only ever a top-level param shape or a ListOf element, never an InputBean field shape, so
            // outerArgOf is never called with one. Defensive read off its own path.
            case ValueShape.JooqRecordInput jr -> jr.sdlPath().outerArgName();
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
