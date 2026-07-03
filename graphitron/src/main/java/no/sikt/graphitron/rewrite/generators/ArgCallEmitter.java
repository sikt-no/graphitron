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
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
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
     * first, then one arg per {@link CallParam}. {@code srcAlias} is the name of the
     * jOOQ table-alias local variable in the caller's scope (e.g. {@code filmTable}),
     * passed through to {@link #buildArgExtraction} so the {@code JooqConvert} branch
     * resolves the same local. {@code conditionsClassName} is retained on the signature
     * but no current extraction arm reads it (R229 retired the {@code TextMapLookup}
     * arm that did).
     */
    public static CodeBlock buildCallArgs(TypeFetcherEmissionContext ctx, List<CallParam> params, String conditionsClassName, String srcAlias) {
        return buildCallArgs(ctx, params, conditionsClassName, srcAlias, null, null);
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
        return buildCallArgs(ctx, params, conditionsClassName, srcAlias, registry, null);
    }

    /**
     * Variant that additionally accepts {@code liftedOuters}, a per-method map from
     * {@link CallSiteExtraction.NestedInputField#outerArgName()} to a local-variable name
     * that already holds the {@code Map<?, ?>} cast of {@code env.getArgument(outerArg)}.
     * When an extraction's outer arg is in the map, the depth-0 step of the chain skips
     * the {@code instanceof Map<?, ?> map1} rebind in favour of a {@code <local> != null}
     * guard against the lifted local. Used by {@link QueryConditionsGenerator} to dedupe
     * the rebind when ≥2 body params on a single method share an outer arg.
     */
    public static CodeBlock buildCallArgs(TypeFetcherEmissionContext ctx, List<CallParam> params,
            String conditionsClassName, String srcAlias, CompositeDecodeHelperRegistry registry,
            Map<String, String> liftedOuters) {
        return buildCallArgs(ctx, params, conditionsClassName, srcAlias, registry, liftedOuters,
            new ArgumentValueSource.Env());
    }

    /**
     * Source-aware variant (R424): {@code source} routes the runtime argument-value read to either
     * {@code env.getArgument(name)} ({@link ArgumentValueSource.Env}, the byte-identical status quo
     * at root/{@code @splitQuery} sites) or {@code <sf>.getArguments().get(name)}
     * ({@link ArgumentValueSource.FromSelectedField}, the two inline emitters, whose {@code env} is
     * the ancestor fetcher's). Only the {@code getArgument}-shaped read forks; the condition and
     * decode logic is source-independent.
     */
    public static CodeBlock buildCallArgs(TypeFetcherEmissionContext ctx, List<CallParam> params,
            String conditionsClassName, String srcAlias, CompositeDecodeHelperRegistry registry,
            Map<String, String> liftedOuters, ArgumentValueSource source) {
        var args = CodeBlock.builder();
        args.add("$L", srcAlias);
        for (var param : params) {
            args.add(", $L", buildArgExtraction(ctx, param, conditionsClassName, srcAlias, registry, liftedOuters, source));
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
     *       {@link CallSiteExtraction} switch ({@code Direct}, {@code EnumValueOf},
     *       {@code ContextArg}, {@code JooqConvert}, {@code NestedInputField},
     *       {@code NodeIdDecodeKeys}, {@code InputBean}) is reused. The {@code srcAlias}
     *       threaded into {@code buildArgExtraction} is
     *       the table local for {@code QueryTableMethodTableField} (the {@code var table = ...}
     *       declared by the per-leaf fetcher) and {@code null} for the service variants
     *       (no source-table context at the root).</li>
     *   <li>{@link ParamSource.Context} — {@code graphitronContext(env).getContextArgument(env, name)}.</li>
     *   <li>{@link ParamSource.DslContext} — literal {@code dsl}; the per-leaf fetcher
     *       declares the local before calling this helper.</li>
     *   <li>{@link ParamSource.Table} — never reached by {@code @service}/{@code @tableMethod}
     *       emission. After R43 {@code @tableMethod} methods declare no Table parameter and
     *       {@code @service} methods never had one. The slot stays on {@link ParamSource} for
     *       {@code @condition}, whose emission lives in {@link no.sikt.graphitron.rewrite.generators.QueryConditionsGenerator}
     *       (not this helper). Callers pass {@code null} for {@code tableExpression}; if a Table
     *       param ever leaked through reflection the helper throws {@link IllegalStateException}.</li>
     *   <li>{@link ParamSource.Sources} / {@link ParamSource.SourceTable} — never reached:
     *       the classifier (FieldBuilder Invariants §2) and the {@code @tableMethod} reflection
     *       check (ServiceCatalog) prevent a root leaf from carrying these. The helper throws
     *       {@link IllegalStateException} if it sees one.</li>
     * </ul>
     *
     * @param method            the developer method to call.
     * @param tableExpression   legacy slot; after R43 every caller passes {@code null} (neither
     *                          {@code @service} nor {@code @tableMethod} methods declare a Table
     *                          parameter). Retained so a leaked {@link ParamSource.Table} surfaces
     *                          as a clear {@link IllegalStateException} rather than a NPE.
     * @param conditionsClassName  retained on the signature; no current extraction arm reads it
     *                             (R229 retired the {@code TextMapLookup} arm that did).
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

    /**
     * Resolves the effective {@link CallSiteExtraction} for a {@link ParamSource.Arg} by routing
     * multi-segment path expressions through {@link CallSiteExtraction.NestedInputField}.
     *
     * <p>Single-segment {@link PathExpr.Head} (single-name baseline): returns {@code arg.extraction()}
     * unchanged. The downstream emitter dispatches on {@code Direct} / {@code EnumValueOf} /
     * {@code JooqConvert} as today.
     *
     * <p>Multi-segment path with no intermediate list segments: wraps the original extraction
     * as the {@code leaf} of a {@code NestedInputField} whose {@code outerArgName} is the head
     * segment and whose {@code path} is the tail segment list. The existing
     * {@link #buildNestedInputFieldExtraction} machinery handles null-safe Map traversal at
     * every level. The leaf step's {@code liftsList} flag is irrelevant for emit (the Map
     * traversal returns the list value directly to the Java parameter).
     *
     * <p>Paths with intermediate {@code liftsList=true} segments are handled separately by
     * {@link #buildListAwarePathExtraction} (routed in {@link #emitForParam} before this helper
     * is reached), so this method does not need to consider that case.
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
     * Such paths require element-wise list traversal in the emitted expression, which is
     * structurally distinct from the {@link CallSiteExtraction.NestedInputField} Map-only chain.
     * Terminal-list segments do not count: at the leaf the value is whatever {@code Map.get}
     * returns and is handed straight to the Java parameter (a {@code List} cast).
     */
    private static boolean hasIntermediateListSegment(PathExpr path) {
        var segments = path.segments();
        for (int i = 1; i < segments.size() - 1; i++) {
            if (segments.get(i).liftsList()) return true;
        }
        return false;
    }

    private static CodeBlock emitForParam(TypeFetcherEmissionContext ctx, MethodRef.Param param, CodeBlock tableExpression,
            CodeBlock sourcesExpression, String conditionsClassName) {
        var source = param.source();
        return switch (source) {
            case ParamSource.Arg arg -> emitArgExpression(ctx, arg, param, conditionsClassName);
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

    /**
     * Per-{@link ParamSource.Arg} dispatcher used by {@link #emitForParam}. Routes paths with
     * an intermediate {@code liftsList=true} segment through {@link #buildListAwarePathExtraction}
     * (element-wise list traversal); routes everything else through the existing
     * {@link #extractionForArg} → {@link #buildArgExtraction} chain (head-only or Map-only
     * traversal). Paths with intermediate-list segments and a non-{@link CallSiteExtraction.Direct}
     * leaf (e.g. enum / text-map / NodeId decode) are rejected with a clear message; that
     * combination has not yet been needed and would require interleaving the leaf transform with
     * the list-element walk.
     */
    private static CodeBlock emitArgExpression(TypeFetcherEmissionContext ctx, ParamSource.Arg arg,
            MethodRef.Param param, String conditionsClassName) {
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
        return buildArgExtraction(
            ctx,
            new CallParam(arg.graphqlArgName(),
                extractionForArg(arg),
                false, param.typeName()),
            conditionsClassName,
            null);
    }

    public static CodeBlock buildArgExtraction(TypeFetcherEmissionContext ctx, CallParam param, String conditionsClassName, String srcAlias) {
        return buildArgExtraction(ctx, param, conditionsClassName, srcAlias, null, null);
    }

    /**
     * Registry-aware variant; see
     * {@link #buildCallArgs(TypeFetcherEmissionContext, List, String, String, CompositeDecodeHelperRegistry)}.
     */
    public static CodeBlock buildArgExtraction(TypeFetcherEmissionContext ctx, CallParam param,
            String conditionsClassName, String srcAlias, CompositeDecodeHelperRegistry registry) {
        return buildArgExtraction(ctx, param, conditionsClassName, srcAlias, registry, null);
    }

    /**
     * Lift-aware variant; see
     * {@link #buildCallArgs(TypeFetcherEmissionContext, List, String, String, CompositeDecodeHelperRegistry, Map)}.
     */
    public static CodeBlock buildArgExtraction(TypeFetcherEmissionContext ctx, CallParam param,
            String conditionsClassName, String srcAlias, CompositeDecodeHelperRegistry registry,
            Map<String, String> liftedOuters) {
        return buildArgExtraction(ctx, param, conditionsClassName, srcAlias, registry, liftedOuters,
            new ArgumentValueSource.Env());
    }

    /**
     * Source-aware variant (R424); see
     * {@link #buildCallArgs(TypeFetcherEmissionContext, List, String, String, CompositeDecodeHelperRegistry, Map, ArgumentValueSource)}.
     * Each arm's {@link ArgumentValueSource.Env} branch is byte-identical to the pre-R424 output, so
     * every root/{@code @splitQuery} site (which passes {@code Env}) is unchanged.
     */
    public static CodeBlock buildArgExtraction(TypeFetcherEmissionContext ctx, CallParam param,
            String conditionsClassName, String srcAlias, CompositeDecodeHelperRegistry registry,
            Map<String, String> liftedOuters, ArgumentValueSource source) {
        return switch (param.extraction()) {
            // Env: a bare, uncast env.getArgument(...) relying on generic-method target-typing.
            // FromSelectedField cannot target-type (Map.get is statically Object), so it casts to
            // the raw component of the param type. Scalar casts are checked; a generic param type
            // (e.g. List<String>) makes the cast unchecked — the $fields host stamps
            // @SuppressWarnings for it (CallParam.emitsUncheckedCastFromSelectedField). This is the
            // first cast the Direct arm has ever emitted; the byte-identical invariant at Env sites
            // holds because the cast is FromSelectedField-scoped.
            case CallSiteExtraction.Direct ignored -> switch (source) {
                case ArgumentValueSource.Env ignoredEnv ->
                    CodeBlock.of("env.getArgument($S)", param.name());
                case ArgumentValueSource.FromSelectedField sf ->
                    CodeBlock.of("($T) $L.getArguments().get($S)",
                        ClassName.bestGuess(rawComponent(param.typeName())), sf.sfLocal(), param.name());
            };
            case CallSiteExtraction.EnumValueOf ev -> {
                var enumClass = ClassName.bestGuess(ev.enumClassName());
                // FromSelectedField reads the wire value via a (checked) (String) cast in both the
                // null-guard and the valueOf call, replacing the Env form's env.<String>getArgument
                // generic target-typing.
                yield switch (source) {
                    case ArgumentValueSource.Env ignoredEnv -> CodeBlock.of(
                        "env.getArgument($S) != null ? $T.valueOf(env.<$T>getArgument($S)) : null",
                        param.name(), enumClass, String.class, param.name());
                    case ArgumentValueSource.FromSelectedField sf -> CodeBlock.of(
                        "($T) $L.getArguments().get($S) != null ? $T.valueOf(($T) $L.getArguments().get($S)) : null",
                        String.class, sf.sfLocal(), param.name(), enumClass, String.class, sf.sfLocal(), param.name());
                };
            }
            // GraphQL context is request-scoped, so the ancestor env is legitimately correct at the
            // inline sites too: this arm stays env-based under BOTH sources (R424).
            case CallSiteExtraction.ContextArg ignored ->
                CodeBlock.of("($T) $L.getContextArgument(env, $S)",
                    rawTypeOfCallParam(param), ctx.graphitronContextCall(), param.name());
            // Coerce the wire value through the column's DataType and its registered Converter via
            // DSL.val(Object, DataType<T>).getValue() — the non-deprecated replacement for
            // DataType.convert(Object), which is @Deprecated(forRemoval = true) in jOOQ 3.20 and
            // would fail the consumer's -Xlint:all -Werror compile (R384 phase a; R267 rule: fix a
            // deprecation-for-removal at the source, never suppress). val coerces eagerly, so
            // getValue() yields the column's Java type with any custom converter applied; null in,
            // null out. The list form reads the shared <name>Keys local the enclosing generator
            // pre-declares (QueryConditionsGenerator / MultiTablePolymorphicEmitter for Env;
            // emitJooqConvertKeyLifts for the inline FromSelectedField sites) — so the list arm is
            // source-independent here; only the pre-lift declaration forks. The scalar arm swaps the
            // wire read (no cast: DSL.val takes Object).
            case CallSiteExtraction.JooqConvert jc -> param.list()
                ? CodeBlock.of("$L.stream().map(k -> $T.val(k, $L.$L.getDataType()).getValue()).toList()",
                    toCamelCase(param.name()) + "Keys", DSL, srcAlias, jc.columnJavaName())
                : CodeBlock.of("$T.val($L, $L.$L.getDataType()).getValue()",
                    DSL, argValueRead(source, param.name()), srcAlias, jc.columnJavaName());
            case CallSiteExtraction.NestedInputField nif ->
                buildNestedInputFieldExtraction(nif.outerArgName(), nif.path(), nif.leaf(),
                    param.typeName(), param.list(), registry, liftedOuters, srcAlias, source);
            case CallSiteExtraction.NodeIdDecodeKeys nidk ->
                buildNodeIdDecodeExtraction(
                    argValueRead(source, param.name()),
                    nidk, param.list(), registry);
            // R311: a jOOQ TableRecord / input-bean @service param — never an inline @reference filter,
            // so FromSelectedField must never reach here. Guard defensively (matching the guard
            // discipline in buildMethodBackedCallArgs) so a mis-wired future caller fails loudly rather
            // than silently emitting the wrong read form; both arms keep the implicit Env read.
            case CallSiteExtraction.InputBean ib -> {
                requireEnv(source, "InputBean", param.name());
                yield buildInputBeanCallExtraction(ib, param.name(), isListShaped(param));
            }
            case CallSiteExtraction.JooqRecord jr -> {
                requireEnv(source, "JooqRecord", param.name());
                yield buildJooqRecordCallExtraction(jr, param.name(), isListShaped(param));
            }
            case CallSiteExtraction.NodeIdDecodeRecord ignored ->
                throw new IllegalStateException(
                    "NodeIdDecodeRecord is an input-bean field leaf only (decoded into a jOOQ record"
                    + " inside the create<Bean> helper); it must not reach the condition/argument"
                    + " call-site emitter for param '" + param.name() + "'");
        };
    }

    /**
     * The uncast runtime argument-value read expression for {@code name} under {@code source}:
     * {@code env.getArgument(name)} for {@link ArgumentValueSource.Env} (byte-identical to the
     * pre-R424 form) or {@code <sf>.getArguments().get(name)} for
     * {@link ArgumentValueSource.FromSelectedField}. Callers that need a cast wrap the result.
     */
    private static CodeBlock argValueRead(ArgumentValueSource source, String name) {
        return switch (source) {
            case ArgumentValueSource.Env ignored -> CodeBlock.of("env.getArgument($S)", name);
            case ArgumentValueSource.FromSelectedField sf ->
                CodeBlock.of("$L.getArguments().get($S)", sf.sfLocal(), name);
        };
    }

    /**
     * Guards a never-inline extraction arm: throws when {@code source} is
     * {@link ArgumentValueSource.FromSelectedField}. The {@code InputBean} / {@code JooqRecord} arms
     * are {@code @service}/input-bean concepts whose producers (root and child-{@code @service}) all
     * keep the implicit {@link ArgumentValueSource.Env}; a {@code FromSelectedField} here means a
     * caller mis-wired the source, which we surface rather than silently emit the wrong read form.
     */
    private static void requireEnv(ArgumentValueSource source, String arm, String paramName) {
        if (source instanceof ArgumentValueSource.FromSelectedField) {
            throw new IllegalStateException(
                "CallSiteExtraction." + arm + " reached buildArgExtraction under a FromSelectedField"
                + " argument source for param '" + paramName + "'; this arm is an @service/input-bean"
                + " concept that is never an inline @reference filter and always reads from env.");
        }
    }

    /**
     * Emits the {@code <name>Keys} pre-lift local(s) that the {@link CallSiteExtraction.JooqConvert}
     * list arm of {@link #buildArgExtraction} reads. A converter-backed list argument reads a
     * pre-declared {@code List<String> <name>Keys} local because the arm composes as an expression
     * and cannot introduce the local itself. {@link QueryConditionsGenerator} and
     * {@link MultiTablePolymorphicEmitter} declare it inline under {@link ArgumentValueSource.Env};
     * only the two inline emitters route it here, always under
     * {@link ArgumentValueSource.FromSelectedField} (hence the narrowed parameter type — there is no
     * {@code Env} caller, so no {@code Env} branch), where {@code sf.getArguments().get(name)} is
     * statically {@code Object} and needs the (unchecked) {@code (List<String>)} cast; the
     * {@code $fields} host stamps {@code @SuppressWarnings} for it (see
     * {@code CallParam.emitsUncheckedCastFromSelectedField}). Dedupes by arg name so two filters
     * sharing a converter-backed list arg declare one local.
     *
     * <p>Fixes a latent defect at the inline sites: without this pre-lift the JooqConvert list arm
     * emits a reference to an undeclared {@code <name>Keys} local (the generated code fails the
     * consumer's compile). Nothing in classification keys converter-backed list args to
     * {@code @splitQuery}, so the shape is inline-reachable in principle.
     */
    static void emitJooqConvertKeyLifts(CodeBlock.Builder stmts, List<? extends WhereFilter> filters,
            ArgumentValueSource.FromSelectedField source) {
        var declared = new LinkedHashSet<String>();
        for (var filter : filters) {
            for (var param : filter.callParams()) {
                if (param.extraction() instanceof CallSiteExtraction.JooqConvert && param.list()
                        && declared.add(param.name())) {
                    stmts.addStatement("$T<$T> $L = ($T<$T>) $L.getArguments().get($S)",
                        List.class, String.class, toCamelCase(param.name()) + "Keys",
                        List.class, String.class, source.sfLocal(), param.name());
                }
            }
        }
    }

    /**
     * Emits the call to the per-bean helper method generated on the enclosing {@code *Fetchers}
     * class. The helper itself is emitted separately by
     * {@link InputBeanInstantiationEmitter#buildSingularHelper} (and plural variant); this method
     * only emits the call expression. The helper name follows the
     * {@code create<TypeName>} / {@code create<TypeName>List} convention from R150.
     */
    private static CodeBlock buildInputBeanCallExtraction(CallSiteExtraction.InputBean ib,
            String argName, boolean list) {
        String simpleName = ib.beanClass().simpleName();
        String helperName = list
            ? "create" + simpleName + "List"
            : "create" + simpleName;
        return CodeBlock.of("$L(env.getArgument($S))", helperName, argName);
    }

    /**
     * R311 sibling of {@link #buildInputBeanCallExtraction} for a jOOQ {@code TableRecord} param: emits
     * the {@code create<Record>} / {@code create<Record>List} call (the helper itself is emitted by
     * {@code JooqRecordInstantiationEmitter}, named from the record class). The helper picks singular
     * vs plural by the param's Java list-shape, identical to the root emitter's choice.
     */
    private static CodeBlock buildJooqRecordCallExtraction(CallSiteExtraction.JooqRecord jr,
            String argName, boolean list) {
        String simpleName = jr.table().recordClass().simpleName();
        String helperName = list
            ? "create" + simpleName + "List"
            : "create" + simpleName;
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
            CallSiteExtraction.NodeIdDecodeKeys nidk, boolean list,
            CompositeDecodeHelperRegistry registry) {
        var decode = nidk.decodeMethod();
        boolean throwOnMismatch = nidk instanceof CallSiteExtraction.ThrowOnMismatch;

        // Every NodeId decode (any arity, skip or throw) lifts to a per-class private helper
        // registered through the collector; the helper bakes in the wire-shape guard, decode call,
        // and key projection as readable statement-form code, so the call site collapses to
        // <name>(wireExpr). This replaces the former inline nested-ternary form (underscore
        // pattern locals plus a Supplier-lambda-throw trick to stay an expression) with a body a
        // developer can breakpoint and read a meaningful stack frame from (R260).
        if (registry == null) {
            // Every NodeId-decoded condition argument is emitted through a generator that owns a
            // CompositeDecodeHelperRegistry and drains it onto the class hosting the call site:
            // QueryConditionsGenerator (<Root>Conditions) for the single-table shim layer, and the
            // <Type>Fetchers collectInto bracket for the fetcher-inline sites (split/lookup rows
            // methods; the multitable branch path, R384 phase b). A registry-less call site
            // reaching a decode is a wiring bug: there would be nowhere to emit the lifted helper.
            // Fail loudly rather than fall back to the inline expression-trick form R260 removed.
            throw new IllegalStateException(
                "NodeId-decode extraction must be lifted into a per-class decode helper, which requires a "
                + "CompositeDecodeHelperRegistry; none was supplied for decode '" + decode.methodName()
                + "'. NodeId-decoded condition arguments must be emitted through a generator that owns "
                + "and drains a registry onto the call site's host class.");
        }
        var mode = throwOnMismatch
            ? CompositeDecodeHelperRegistry.Mode.THROW
            : CompositeDecodeHelperRegistry.Mode.SKIP;
        String helperName = registry.register(decode, mode, list);
        return CodeBlock.of("$L($L)", helperName, wireExpr);
    }

    /**
     * R186: a null-safe nested-Map value descent reading from a local that already holds a
     * {@code Map<?, ?>} (the mutation emitters' {@code in} / {@code row} argument-value maps). For a
     * single-segment {@code path} the result is the byte-identical {@code mapLocal.get(key)}; for a
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
     * {@link CallSiteExtraction.NestedInputField}. For {@code path = [k1, k2, ..., kN]} the
     * generated expression is a chain of {@code instanceof Map<?,?>} ternaries:
     *
     * <pre>
     *     env.getArgument("outer") instanceof Map&lt;?, ?&gt; map1
     *         ? (map1.get("k1") instanceof Map&lt;?, ?&gt; map2
     *             ? (... ? (LeafType) mapN.get("kN") : null)
     *             : null)
     *         : null
     * </pre>
     *
     * <p>Pattern-variable bindings {@code map1..mapN} are scoped to the ternary's "then" branch,
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
            CompositeDecodeHelperRegistry registry, Map<String, String> liftedOuters,
            String srcAlias, ArgumentValueSource source) {
        // For a Direct leaf the Map.get value is cast directly to the parameter type. For a
        // NodeIdDecodeKeys leaf the leaf cast is omitted -- the decode chain takes Object and
        // its own instanceof guard validates the runtime shape -- so the inner pattern stays
        // "conditional" under -source 17 (no `(List<String>) ... instanceof List` chain).
        boolean nodeIdLeaf = leaf instanceof CallSiteExtraction.NodeIdDecodeKeys;
        // When the outer arg has already been lifted to a typed Map<?, ?> local, depth 0 of the
        // chain references the local directly under a null-check; otherwise it falls back to the
        // inline `env.getArgument(outer) instanceof Map<?, ?> map1` rebind.
        // liftedOuters is populated only by the Env sites (QueryConditionsGenerator /
        // MultiTablePolymorphicEmitter); the two inline sites pass null, so under FromSelectedField
        // topBinding is always null and the depth-0 read routes through the source (R424).
        String topBinding = liftedOuters != null ? liftedOuters.get(outerArgName) : null;
        CodeBlock root = topBinding != null
            ? CodeBlock.of("$L", topBinding)
            : argValueRead(source, outerArgName);

        if (nodeIdLeaf) {
            CodeBlock mapChain = buildMapChain(root, path, 0, /* leafType= */ null, topBinding);
            return buildNodeIdDecodeExtraction(mapChain, (CallSiteExtraction.NodeIdDecodeKeys) leaf,
                list, registry);
        }
        // A JooqConvert leaf (a nested [ID!]/ID @field over a converted column, R384 phase a)
        // coerces the traversal result through the column's DataType, mirroring the top-level
        // JooqConvert arm's DSL.val(...).getValue() form. The scalar form needs no null guard
        // (val takes Object; null in, null out); the list form guards with an instanceof List
        // pattern so the traversal runs once. Like NodeIdDecodeKeys, the leaf cast is omitted --
        // the coercion owns the runtime shape.
        if (leaf instanceof CallSiteExtraction.JooqConvert jc) {
            CodeBlock mapChain = buildMapChain(root, path, 0, /* leafType= */ null, topBinding);
            if (list) {
                return CodeBlock.of(
                    "($L) instanceof $T<?> keys ? keys.stream().map(k -> $T.val(k, $L.$L.getDataType()).getValue()).toList() : null",
                    mapChain, List.class, DSL, srcAlias, jc.columnJavaName());
            }
            return CodeBlock.of("$T.val($L, $L.$L.getDataType()).getValue()",
                DSL, mapChain, srcAlias, jc.columnJavaName());
        }
        ClassName rawLeaf = ClassName.bestGuess(rawComponent(leafTypeName));
        TypeName castTarget = list
            ? ParameterizedTypeName.get(ClassName.get(List.class), rawLeaf)
            : rawLeaf;
        return buildMapChain(root, path, 0, castTarget, topBinding);
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
            // own runtime guard (used by the NodeIdDecodeKeys leaf path).
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
