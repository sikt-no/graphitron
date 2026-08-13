package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.PathExpr;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;

import java.util.List;
import java.util.Map;

/**
 * Emits argument-list and per-argument extraction code for method-backed developer calls (the
 * child {@code @service} arms). The condition-method call surface this class once shared with the
 * WHERE emitters retired with call-site convergence: condition extraction renders inside the
 * condition glue ({@code no.sikt.graphitron.render.ConditionGlueRenderer}), and every WHERE
 * consumer emits one glue call ({@link ConditionGlueCall}). What stays here is the
 * {@link ParamSource}-driven service-call emission, whose reads are always environment-rooted.
 */
public final class ArgCallEmitter {

    private ArgCallEmitter() {}

    /**
     * Builds the argument list for a method-backed call, iterating {@link MethodRef#params()}
     * in declaration order and emitting one expression per {@link ParamSource} variant
     * (see {@link #emitForParam}). Both call sites are child {@code @service} arms in
     * {@code TypeFetcherGenerator}'s dispatch, composing the shell's service-call fragment for
     * the launcher renderer's lift and delegate arms. Root service permits emit their calls
     * elsewhere and do not reach this helper.
     *
     * <p>There is no implicit first argument: the helper emits exactly the comma-separated
     * argument expressions in user-declared order, letting the caller wrap with whatever
     * surrounding code (a {@code dsl} local, a projection, a {@code return} statement) the
     * per-leaf shape requires.
     *
     * @param method            the developer method to call.
     * @param tableExpression   every caller passes {@code null}: a {@code @service} method
     *                          declares no Table parameter. The {@link ParamSource.Table} slot
     *                          exists for {@code @condition}, whose emission lives in the
     *                          condition glue renderer. The slot is retained so a
     *                          leaked Table param surfaces as a clear
     *                          {@link IllegalStateException} rather than a NPE.
     * @param sourcesExpression the {@link CodeBlock} to emit at the {@link ParamSource.Sources}
     *                          slot; both callers pass the batch {@code keys} parameter. The
     *                          {@code null} arm rejects a Sources param outright and is
     *                          caller-unreachable today, retained as a guard for a future
     *                          caller with no batch to supply.
     * @param outputPackage     the run's output package, locating the generated
     *                          {@code TenantConnections} carrier for the {@code $session} guard.
     * @param fieldCoordinate   the emitting field ({@code Type.field}), baked into the
     *                          {@code $session} guard's failure message.
     */
    public static CodeBlock buildMethodBackedCallArgs(TypeFetcherEmissionContext ctx, MethodRef method, CodeBlock tableExpression,
            CodeBlock sourcesExpression, String outputPackage, String fieldCoordinate) {
        var args = CodeBlock.builder();
        boolean first = true;
        for (var param : method.params()) {
            if (!first) args.add(", ");
            first = false;
            args.add(emitForParam(ctx, param, tableExpression, sourcesExpression, outputPackage, fieldCoordinate));
        }
        return args.build();
    }

    /**
     * Resolves the effective {@link CallSiteExtraction} for a {@link ParamSource.Arg}: a
     * single-segment {@link PathExpr.Head} returns {@code arg.extraction()} unchanged, and a
     * multi-segment path wraps it as the {@code leaf} of a
     * {@link CallSiteExtraction.NestedInputField} (head segment as {@code outerArgName}, tail
     * as {@code path}) so {@link #buildNestedInputFieldExtraction} handles the null-safe Map
     * traversal. Paths with intermediate {@code liftsList=true} segments never reach here;
     * {@link #emitArgExpression} routes them to {@link #buildListAwarePathExtraction} first.
     */
    private static CallSiteExtraction extractionForArg(ParamSource.Arg arg) {
        if (arg.path().isHead()) {
            return arg.extraction();
        }
        var segments = arg.path().segments();
        var tail = segments.subList(1, segments.size()).stream().map(PathExpr.Segment::name).toList();
        return new CallSiteExtraction.NestedInputField(arg.path().headName(), tail, arg.extraction());
    }

    /**
     * True when {@code path} contains at least one non-terminal {@code liftsList=true} segment.
     * Such paths require element-wise list traversal, structurally distinct from the
     * {@link CallSiteExtraction.NestedInputField} Map-only chain. Terminal-list segments do
     * not count: at the leaf the {@code Map.get} value is handed straight to the Java
     * parameter (a {@code List} cast).
     */
    private static boolean hasIntermediateListSegment(PathExpr path) {
        var segments = path.segments();
        for (int i = 1; i < segments.size() - 1; i++) {
            if (segments.get(i).liftsList()) return true;
        }
        return false;
    }

    private static CodeBlock emitForParam(TypeFetcherEmissionContext ctx, MethodRef.Param param, CodeBlock tableExpression,
            CodeBlock sourcesExpression, String outputPackage, String fieldCoordinate) {
        var source = param.source();
        return switch (source) {
            case ParamSource.Arg arg -> emitArgExpression(ctx, arg, param);
            case ParamSource.Context ignored ->
                CodeBlock.of("($T) $L.getContextArgument(env, $S)",
                    rawTypeOf(param), ctx.graphitronContextCall(), param.name());
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
                            + "root-level @service must reject this at classifier time (ServiceDirectiveResolver's batch-at-root classify arm); "
                            + "child-level rows-method emitters must pass a sourcesExpression: param '"
                            + param.name() + "'");
                }
                yield sourcesExpression;
            }
            case ParamSource.SourceTable ignored ->
                throw new IllegalStateException(
                    "ParamSource.SourceTable reached buildMethodBackedCallArgs — SourceTable is a child-field concept, unreachable at root: param '"
                    + param.name() + "'");
            case ParamSource.SourceColumn ignored ->
                throw new IllegalStateException(
                    "ParamSource.SourceColumn reached buildMethodBackedCallArgs — SourceColumn is a "
                    + "routine-binding concept (@routine columnMapping), never a MethodRef param source: param '"
                    + param.name() + "'");
            case ParamSource.SessionSeam ignored ->
                throw new IllegalStateException(
                    "ParamSource.SessionSeam reached buildMethodBackedCallArgs — the seam parameter exists "
                    + "only on <mount>/<unmount> hook methods, which the generated hook class calls directly: param '"
                    + param.name() + "'");
            // The $session sigil: the handle rides the resolved DSLContext's own per-Configuration
            // data() map, written once at mount by the connection runtime. Reading it through the
            // carrier's guarded accessor off the dsl local (never graphQLContext) is what scopes
            // the read per pinned connection (a tenant-routed call sees that tenant's handle) and
            // what makes an unmounted connection (the escape-hatch factory) a located throw
            // instead of a silently bound null.
            case ParamSource.SessionHandle ignored -> {
                if (fieldCoordinate == null) {
                    throw new IllegalStateException(
                        "ParamSource.SessionHandle reached buildMethodBackedCallArgs without a"
                        + " fieldCoordinate — the $session guard bakes the coordinate into its failure"
                        + " message, so every caller must supply it: param '" + param.name() + "'");
                }
                yield CodeBlock.of("$T.sessionHandle(dsl, $S)",
                    TenantDslEmitter.tenantConnectionsClass(outputPackage), fieldCoordinate);
            }
        };
    }

    /**
     * Per-{@link ParamSource.Arg} dispatcher used by {@link #emitForParam}. Routes paths with
     * an intermediate {@code liftsList=true} segment through {@link #buildListAwarePathExtraction}
     * (element-wise list traversal, {@link CallSiteExtraction.Direct} leaves only: any other
     * leaf transform would have to interleave with the list-element walk, so it is rejected);
     * everything else routes through {@link #extractionForArg} and {@link #buildArgExtraction}.
     */
    private static CodeBlock emitArgExpression(TypeFetcherEmissionContext ctx, ParamSource.Arg arg,
            MethodRef.Param param) {
        if (hasIntermediateListSegment(arg.path())) {
            if (!(arg.extraction() instanceof CallSiteExtraction.Direct)) {
                throw new IllegalStateException(
                    "argMapping path expression '" + arg.path().asString() + "' on parameter '"
                    + param.name() + "' has an intermediate list segment combined with a "
                    + arg.extraction().getClass().getSimpleName() + " leaf transform — "
                    + "element-wise list traversal currently supports only Direct leaves");
            }
            return buildListAwarePathExtraction(arg.path(), param.typeName());
        }
        // The head is the right read: it names the slot the extraction roots on, and any tail
        // segments ride the NestedInputField extraction extractionForArg mints.
        return buildArgExtraction(ctx,
            new CallParam(arg.path().headName(), extractionForArg(arg), false, param.typeName()));
    }

    /**
     * The environment-rooted extraction expression for one service-call argument. Every read is
     * {@code env}-based (a service call has no {@code SelectedField} and no table alias in
     * scope), which is why the column-coercing and decode arms throw: those extraction kinds are
     * condition-binding concepts that render inside the condition glue, never a {@code @service}
     * parameter binding.
     */
    private static CodeBlock buildArgExtraction(TypeFetcherEmissionContext ctx, CallParam param) {
        return switch (param.extraction()) {
            // A bare, uncast env.getArgument(...) relying on generic-method target-typing.
            case CallSiteExtraction.Direct ignored ->
                CodeBlock.of("env.getArgument($S)", param.name());
            case CallSiteExtraction.EnumValueOf ev -> {
                var enumClass = ClassName.bestGuess(ev.enumClassName());
                yield CodeBlock.of(
                    "env.getArgument($S) != null ? $T.valueOf(env.<$T>getArgument($S)) : null",
                    param.name(), enumClass, String.class, param.name());
            }
            case CallSiteExtraction.ContextArg ignored ->
                CodeBlock.of("($T) $L.getContextArgument(env, $S)",
                    rawTypeOfCallParam(param), ctx.graphitronContextCall(), param.name());
            case CallSiteExtraction.NestedInputField nif ->
                buildNestedInputFieldExtraction(nif.outerArgName(), nif.path(), nif.leaf(),
                    param.typeName(), param.list());
            case CallSiteExtraction.InputBean ib ->
                buildInputBeanCallExtraction(ctx, ib, param.name(), isListShaped(param));
            case CallSiteExtraction.JooqRecord jr ->
                buildJooqRecordCallExtraction(ctx, jr, param.name(), isListShaped(param));
            case CallSiteExtraction.JooqConvert ignored ->
                throw new IllegalStateException(
                    "CallSiteExtraction.JooqConvert reached the service-call argument emitter for"
                    + " param '" + param.name() + "'; column coercion is a condition-binding"
                    + " concept rendered inside the condition glue");
            case CallSiteExtraction.NodeIdDecodeKeys ignored ->
                throw new IllegalStateException(
                    "CallSiteExtraction.NodeIdDecodeKeys reached the service-call argument emitter"
                    + " for param '" + param.name() + "'; NodeId decodes are condition-binding"
                    + " concepts rendered inside the condition glue");
            case CallSiteExtraction.NodeIdDecodeRecord ignored ->
                throw new IllegalStateException(
                    "NodeIdDecodeRecord is an input-bean field leaf only (decoded into a jOOQ record"
                    + " inside the create<Bean> helper); it must not reach the service-call"
                    + " argument emitter for param '" + param.name() + "'");
        };
    }

    /**
     * Emits the call to the per-bean helper method generated on the enclosing {@code *Fetchers}
     * class. The helper itself is emitted separately by
     * {@link InputBeanInstantiationEmitter#buildSingularHelper} (and plural variant); this method
     * only emits the call expression. The helper name follows the
     * {@code create<TypeName>} / {@code create<TypeName>List} convention.
     */
    private static CodeBlock buildInputBeanCallExtraction(TypeFetcherEmissionContext ctx,
            CallSiteExtraction.InputBean ib, String argName, boolean list) {
        var names = ctx.fetchersHelperNames();
        String helperName = list ? names.createPlural(ib.beanClass()) : names.createSingular(ib.beanClass());
        return CodeBlock.of("$L(env.getArgument($S))", helperName, argName);
    }

    /**
     * Sibling of {@link #buildInputBeanCallExtraction} for a jOOQ {@code TableRecord} param: emits
     * the {@code create<Record>} / {@code create<Record>List} call (the helper itself is emitted by
     * {@code JooqRecordInstantiationEmitter}). The helper name resolves through the class-level
     * {@link JooqRecordHelperNames} on {@code ctx}, keyed by this carrier's binding shape rather
     * than the record class, so the call routes to the same helper the drain emitted for its shape
     * and distinctly from a sibling field binding the same record through a different shape.
     * Singular vs plural follows the param's Java list-shape.
     */
    private static CodeBlock buildJooqRecordCallExtraction(TypeFetcherEmissionContext ctx,
            CallSiteExtraction.JooqRecord jr, String argName, boolean list) {
        var names = ctx.jooqRecordHelperNames();
        String helperName = list ? names.pluralName(jr) : names.singularName(jr);
        return CodeBlock.of("$L(env.getArgument($S))", helperName, argName);
    }

    /**
     * Returns true when the param's Java type is a {@code List<...>} or {@code Set<...>}. Used by
     * the {@link CallSiteExtraction.InputBean} arm to pick between the singular and plural helper.
     * {@link CallParam#list()} would also work, but the only caller that constructs a CallParam
     * for service params ({@link #emitArgExpression}) hardcodes {@code list=false}. Inspecting
     * the type name is more direct and keeps the InputBean arm self-contained.
     */
    private static boolean isListShaped(CallParam param) {
        if (param.list()) return true;
        String t = param.typeName();
        return t.startsWith("java.util.List<") || t.startsWith("java.util.Set<");
    }

    /**
     * A null-safe nested-Map value descent reading from a local that already holds a
     * {@code Map<?, ?>} (the mutation emitters' {@code in} / {@code row} argument-value maps). For a
     * single-segment {@code path} the result is {@code mapLocal.get(key)}; for a
     * deeper path it is the {@code instanceof Map<?, ?>} ternary chain {@link #buildMapChain}
     * produces, yielding {@code null} if any intermediate level is absent or not a {@code Map}. The
     * descent applies no leaf cast (the value flows into {@code DSL.val(value, dataType)} / a decode
     * helper that takes {@code Object}), so it is shared by the SET-value, WHERE-value, INSERT-cell
     * and NodeId-decode-source reads alike.
     */
    public static CodeBlock nestedMapValueExpr(String mapLocal, List<String> path) {
        if (path.size() == 1) {
            return CodeBlock.of("$L.get($S)", mapLocal, path.get(0));
        }
        return buildMapChain(CodeBlock.of("$L", mapLocal), path, 0, /* leafType= */ null, mapLocal);
    }

    /**
     * Emits a null-safe nested-Map traversal expression for
     * {@link CallSiteExtraction.NestedInputField}, rooted at {@code env.getArgument(outer)}. For
     * {@code path = [k1, k2, ..., kN]} the generated expression is a chain of
     * {@code instanceof Map<?,?>} ternaries yielding {@code null} at any absent level, with the
     * leaf cast to the raw component of {@code leafTypeName}. The decode and converter leaf
     * transforms never reach this service-path form ({@link #buildArgExtraction} rejects them);
     * only the plain cast leaf remains.
     */
    private static CodeBlock buildNestedInputFieldExtraction(String outerArgName, List<String> path,
            CallSiteExtraction leaf, String leafTypeName, boolean list) {
        if (leaf instanceof CallSiteExtraction.NodeIdDecodeKeys
                || leaf instanceof CallSiteExtraction.JooqConvert) {
            throw new IllegalStateException(
                "a " + leaf.getClass().getSimpleName() + " leaf reached the service-call nested"
                + " extraction; decode and column coercion render inside the condition glue");
        }
        CodeBlock root = CodeBlock.of("env.getArgument($S)", outerArgName);
        ClassName rawLeaf = ClassName.bestGuess(rawComponent(leafTypeName));
        TypeName castTarget = list
            ? ParameterizedTypeName.get(ClassName.get(List.class), rawLeaf)
            : rawLeaf;
        return buildMapChain(root, path, 0, castTarget, null);
    }

    /**
     * Builds the depth-0 step's ternary, recursing for inner steps. When {@code topBinding} is
     * non-null it names a local that is already a {@code Map<?, ?>}, so depth 0 skips the
     * {@code instanceof Map<?, ?> map1} check and emits {@code <topBinding> != null ?
     * (..._)  : null} instead. Inner steps always rebind via {@code map2, map3, ...}.
     */
    private static CodeBlock buildMapChain(CodeBlock currentExpr, List<String> path, int depth,
            TypeName leafType, String topBinding) {
        String key = path.get(depth);
        boolean isLeaf = depth == path.size() - 1;
        boolean liftedHead = topBinding != null && depth == 0;
        String binding = liftedHead ? topBinding : "map" + (depth + 1);

        if (isLeaf) {
            // leafType == null means "do not cast the Map.get result" -- the consumer applies its
            // own runtime guard (the mutation emitters' DSL.val / decode-helper reads).
            if (liftedHead) {
                if (leafType == null) {
                    return CodeBlock.of("$L != null ? $L.get($S) : null", binding, binding, key);
                }
                return CodeBlock.of("$L != null ? ($T) $L.get($S) : null",
                    binding, leafType, binding, key);
            }
            if (leafType == null) {
                return CodeBlock.of("$L instanceof $T<?, ?> $L ? $L.get($S) : null",
                    currentExpr, Map.class, binding, binding, key);
            }
            return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($T) $L.get($S) : null",
                currentExpr, Map.class, binding, leafType, binding, key);
        }
        CodeBlock next = CodeBlock.of("$L.get($S)", binding, key);
        if (liftedHead) {
            return CodeBlock.of("$L != null ? ($L) : null",
                binding, buildMapChain(next, path, depth + 1, leafType, null));
        }
        return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($L) : null",
            currentExpr, Map.class, binding, buildMapChain(next, path, depth + 1, leafType, null));
    }

    private static String rawComponent(String typeName) {
        int lt = typeName.indexOf('<');
        return lt < 0 ? typeName : typeName.substring(0, lt);
    }

    /**
     * Returns the raw {@link TypeName} for the {@code $T.class} literal at a
     * {@link ParamSource.Context} call site. Reads the structured {@link TypeName} off
     * {@link MethodRef.Param.Typed#javaType()} and collapses any parameterised type to its
     * erasure, since {@code Class<T>} cast checks erase generics at runtime. Cast to
     * {@link MethodRef.Param.Typed} is safe inside the {@link ParamSource.Context} arm:
     * {@link MethodRef.Param.Sourced} carries {@link ParamSource.Sources}, never
     * {@link ParamSource.Context}.
     */
    private static TypeName rawTypeOf(MethodRef.Param param) {
        var typed = (MethodRef.Param.Typed) param;
        TypeName t = typed.javaType();
        if (t instanceof ParameterizedTypeName p) return p.rawType();
        return t;
    }

    /**
     * Returns the raw {@link TypeName} for the Java cast literal at a
     * {@link CallSiteExtraction.ContextArg} call site reached via {@link CallParam}. Reads the
     * structured {@link TypeName} off {@link CallParam#javaType()} (populated by
     * {@link MethodRef#callParams()} from {@link MethodRef.Param.Typed#javaType()}) and collapses
     * any parameterised type to its erasure: the same lift {@link #rawTypeOf} applies to the
     * {@link MethodRef.Param.Typed} arm above, so both Context arms read identical structural
     * data rather than re-parsing the string form via {@link ClassName#bestGuess(String)}.
     */
    private static TypeName rawTypeOfCallParam(CallParam param) {
        TypeName t = param.javaType();
        if (t instanceof ParameterizedTypeName p) return p.rawType();
        return t;
    }

    /**
     * Emits a list-aware nested traversal for a path expression that contains one or more
     * intermediate {@code liftsList=true} segments. Each intermediate list lifts the rest of
     * the walk into a {@code .stream().map(...).toList()} producing one extra {@code List<>}
     * dimension on the result; the terminal segment, list-shaped or not, just casts the
     * {@code Map.get} value (a list-shaped terminal contributes its own {@code List<>}
     * dimension via the cast, not via streaming).
     *
     * <p>Path {@code [head, items*, id]} where {@code items*} is list-shaped and {@code id} is
     * scalar (Java parameter type {@code List<Integer>}) emits roughly:
     * <pre>
     *     env.getArgument("head") instanceof Map&lt;?, ?&gt; map1
     *         ? (map1.get("items") instanceof List&lt;?&gt; list2
     *             ? list2.stream()
     *                 .map(elem3 -&gt; elem3 instanceof Map&lt;?, ?&gt; map4
     *                     ? (Integer) map4.get("id") : null)
     *                 .toList()
     *             : null)
     *         : null
     * </pre>
     *
     * <p>Path {@code [head, groups*, items*, id]} (Java parameter {@code List<List<Integer>>})
     * emits a doubly nested stream: the outer over groups produces inner lists, and the inner
     * stream over each group's items produces {@code List<Integer>}. Null at any depth
     * short-circuits to {@code null} at that depth.
     *
     * <p>Restrictions:
     * <ul>
     *   <li>The leaf transform must be {@link CallSiteExtraction.Direct}; the dispatcher
     *       {@link #emitArgExpression} rejects other leaf shapes before reaching this helper.</li>
     *   <li>The Java parameter type's {@code List<>} wrap count must equal the number of
     *       {@code liftsList=true} segments on the path. The classifier (path resolution
     *       in {@link no.sikt.graphitron.rewrite.ArgBindingMap}) trusts the schema; if the
     *       Java type drifts from the schema shape, the cast inside the lambda will fail at
     *       runtime — which is the same failure mode as a wrong single-name binding.</li>
     * </ul>
     */
    private static CodeBlock buildListAwarePathExtraction(PathExpr path, String leafTypeName) {
        var segments = path.segments();
        String headName = segments.get(0).name();
        int liftCount = 0;
        for (int i = 1; i < segments.size(); i++) {
            if (segments.get(i).liftsList()) liftCount++;
        }
        // Java parameter type wraps in one List<> for each liftsList=true segment (intermediate or
        // terminal). Strip those wraps to find the innermost element type (e.g. "java.lang.Integer").
        String innerLeafType = stripListWraps(leafTypeName, liftCount);
        var tail = segments.subList(1, segments.size());
        return walkSegments(
            CodeBlock.of("env.getArgument($S)", headName),
            tail,
            innerLeafType,
            new int[]{0});
    }

    /**
     * Strips {@code n} leading {@code java.util.List<...>} wraps from {@code typeName}, returning
     * the inner type. {@code stripListWraps("java.util.List<java.util.List<java.lang.Integer>>", 2)}
     * returns {@code "java.lang.Integer"}. If the wrap count exceeds the actual nesting (which
     * indicates a classifier/schema drift), the helper stops at the innermost {@code <>} pair
     * found rather than throwing — the resulting cast may fail at runtime, which surfaces the
     * mismatch loudly enough.
     */
    private static String stripListWraps(String typeName, int n) {
        String t = typeName;
        for (int i = 0; i < n; i++) {
            int lt = t.indexOf('<');
            int gt = t.lastIndexOf('>');
            if (lt < 0 || gt < 0) return t;
            t = t.substring(lt + 1, gt).trim();
        }
        return t;
    }

    /**
     * Recursive emit for the list-aware walker. {@code currentExpr} is the {@code Object}-typed
     * value at the current depth; {@code tail} is the remaining segments to traverse from there;
     * {@code innerLeafType} is the cast target for the innermost {@code Map.get} (after
     * {@code List<>} wraps have been stripped); {@code counter} is shared across recursive calls
     * so binding names {@code map1, list2, elem3, ...} stay distinct within the same expression.
     */
    private static CodeBlock walkSegments(CodeBlock currentExpr, List<PathExpr.Segment> tail,
            String innerLeafType, int[] counter) {
        var seg = tail.get(0);
        var rest = tail.subList(1, tail.size());
        boolean isLast = rest.isEmpty();
        int mNum = ++counter[0];
        String mBind = "map" + mNum;

        if (isLast) {
            ClassName rawLeaf = ClassName.bestGuess(rawComponent(innerLeafType));
            TypeName castTarget = seg.liftsList()
                ? ParameterizedTypeName.get(ClassName.get(List.class), rawLeaf)
                : rawLeaf;
            return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($T) $L.get($S) : null",
                currentExpr, Map.class, mBind, castTarget, mBind, seg.name());
        }

        if (seg.liftsList()) {
            int lNum = ++counter[0];
            String lBind = "list" + lNum;
            int eNum = ++counter[0];
            String eBind = "elem" + eNum;
            CodeBlock recursed = walkSegments(CodeBlock.of("$L", eBind), rest, innerLeafType, counter);
            return CodeBlock.of(
                "$L instanceof $T<?, ?> $L ? ($L.get($S) instanceof $T<?> $L "
                + "? $L.stream().map($L -> $L).toList() : null) : null",
                currentExpr, Map.class, mBind, mBind, seg.name(),
                List.class, lBind, lBind, eBind, recursed);
        }

        CodeBlock next = CodeBlock.of("$L.get($S)", mBind, seg.name());
        CodeBlock recursed = walkSegments(next, rest, innerLeafType, counter);
        return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($L) : null",
            currentExpr, Map.class, mBind, recursed);
    }
}
