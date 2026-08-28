package no.sikt.graphitron.render;

import no.sikt.graphitron.command.ArgBinding;
import no.sikt.graphitron.command.AuthoredMethodRef;
import no.sikt.graphitron.command.ColumnTerm;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.MatchKind;
import no.sikt.graphitron.command.OuterLift;
import no.sikt.graphitron.command.Predicate;
import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The condition glue renderer: interprets {@link ConditionCommand} rows into the
 * {@code <Parent>Conditions} classes, one class per distinct glue owner (the type-keyed GROUP BY),
 * one public static method per row plus one per facet fragment. Takes rows and the output
 * package; no schema, no fact hierarchy, total over what it is handed with nothing to branch on.
 *
 * <p>The glue body is the readability contract: one named local per argument value (extraction,
 * decode, enum coercion, with nested-path {@code instanceof} chains on the local's right-hand
 * side), then the predicate composition, generated terms with their presence guards and authored
 * predicates as calls into developer code. All argument-value reads root at the {@code args} map
 * parameter; callers supply {@code env.getArguments()} or {@code <sf>.getArguments()}, which are
 * the same coerced map ({@code DataFetchingEnvironment.getArgument} is
 * {@code getArguments().get}). A row whose bindings read the request context
 * ({@code @condition(contextArguments:)}) takes the env-appending signature: the environment is
 * appended after the map, the context locals read through the class's own
 * {@code graphitronContext(env)} helper ({@link RequestContextHelper}), and the fork is
 * row-grained so a coordinate's glue method and facet fragments agree.
 *
 * <p>Reach renders as a correlated {@code EXISTS} over the row's {@link ReachPath} hops, whose
 * {@link no.sikt.graphitron.rewrite.model.On} arms this class does not switch on itself: both the
 * hop-0 correlation and the walk-back bridging joins dispatch through {@link PathFragments}, the
 * same arms the projection rail uses. SQL aliases are
 * runtime-prefixed on the base table's name ({@code table.getName() + "_fkt0_0"}): glue methods
 * are per-coordinate scopes, but two glue methods can land in one query (a polymorphic root's
 * participant branches), so a static alias could collide across branches; one convention beats
 * two, and the recursion-prone inline sites already prove this one.
 */
public final class ConditionGlueRenderer {

    private ConditionGlueRenderer() {}

    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final TypeName ARGS_MAP =
        ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class));

    /**
     * Renders one conditions class per distinct glue owner among {@code rows}, in row order.
     *
     * <p>{@code keyProjections} is the graph's projected {@code argMapping} bindings: a condition
     * parameter bound to a path that opens a {@code @nodeId} reads its column off a decoded record
     * rather than off the args map. The decode body is hosted on this class, which is the reason this
     * site needs more than the routine site did: the {@code decode<Record>} bodies a
     * {@code <Type>Fetchers} class hosts are unreachable from here, so a conditions class mints its own
     * through {@link RecordDecodeHelperRegistry}.
     */
    public static List<TypeSpec> render(List<ConditionCommand> rows, String outputPackage,
            KeyProjectionRelation keyProjections) {
        var byOwner = new LinkedHashMap<UnitRef, List<ConditionCommand>>();
        for (var row : rows) {
            byOwner.computeIfAbsent(row.glue().owner(), k -> new ArrayList<>()).add(row);
        }
        var out = new ArrayList<TypeSpec>(byOwner.size());
        for (var entry : byOwner.entrySet()) {
            var classBuilder = TypeSpec.classBuilder(entry.getKey().simpleName()).addModifiers(Modifier.PUBLIC);
            CompositeDecodeHelperRegistry.collectInto(classBuilder, outputPackage, registry ->
                RecordDecodeHelperRegistry.collectInto(classBuilder, outputPackage, decodes ->
                RequestContextHelper.collectInto(classBuilder, outputPackage, contextHelper -> {
                    // One projection host per class: the relation is the graph's, and reaching a decode
                    // registers its body here, so a called helper cannot go un-emitted.
                    var keyHost = new ProjectedKeyHost(keyProjections,
                        projection -> decodes.register(NodeIdEncoderRef.of(outputPackage),
                            projection.typeId(), projection.nodeTypeName(),
                            projection.keyColumns(), projection.nodeTable()));
                    for (var row : entry.getValue()) {
                        boolean takesEnv = row.readsRequestContext();
                        classBuilder.addMethod(buildGlueMethod(
                            row.glue().methodName(), row.table().tableClass(),
                            row.predicates(), row.lifts(), takesEnv, registry, contextHelper,
                            keyHost.at(row.coordinate())));
                        for (var fragment : row.facets()) {
                            classBuilder.addMethod(buildGlueMethod(
                                fragment.method().methodName(), row.table().tableClass(),
                                fragment.predicates(), fragment.lifts(), takesEnv, registry, contextHelper,
                                keyHost.at(row.coordinate())));
                        }
                    }
                })));
            out.add(classBuilder.build());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // One glue method
    // ------------------------------------------------------------------------------------------

    private static MethodSpec buildGlueMethod(String methodName, TypeName jooqTableClass,
            List<Predicate> predicates, List<OuterLift> lifts, boolean takesEnv,
            CompositeDecodeHelperRegistry registry, RequestContextHelper contextHelper,
            ProjectedKeyReads keys) {
        var builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(jooqTableClass, "table")
            .addParameter(ARGS_MAP, "args");
        if (takesEnv) {
            builder.addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env");
        }

        var bindings = distinctBindings(predicates);
        if (bindings.stream().anyMatch(b -> emitsUncheckedLocalCast(b.param()))) {
            builder.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked")
                .build());
        }

        var liftLocals = new LinkedHashMap<String, String>();
        for (var lift : lifts) {
            liftLocals.put(lift.outerArgName(), lift.localName());
            builder.addStatement("$T<?, ?> $L = args.get($S) instanceof $T<?, ?> map ? map : null",
                MAP, lift.localName(), lift.outerArgName(), MAP);
        }
        // Composed before it is emitted: a projected binding registers a node-id decode with the
        // sink, and that materialisation has to be declared ahead of every local reading it. With no
        // projection the sink contributes nothing and this emits exactly what the loop used to.
        var bindingLocals = new ArrayList<CodeBlock>();
        for (var binding : bindings) {
            bindingLocals.add(extractionExpr(binding.param(), liftLocals, registry, contextHelper, keys));
        }
        builder.addCode(keys.declarations());
        for (int i = 0; i < bindings.size(); i++) {
            builder.addStatement("$T $L = $L",
                localType(bindings.get(i).param()), bindings.get(i).localName(), bindingLocals.get(i));
        }

        var aliasesByReach = declareReachAliases(builder, predicates);

        builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
        for (var predicate : predicates) {
            switch (predicate) {
                case Predicate.Generated generated -> {
                    for (var term : generated.terms()) {
                        appendGuardedAnd(builder, term, termExpr(term, aliasesByReach));
                    }
                }
                case Predicate.Authored authored ->
                    builder.addStatement("condition = condition.and($L)",
                        authoredExpr(authored, aliasesByReach));
            }
        }
        builder.addStatement("return condition");
        return builder.build();
    }

    /** The method's binding set in first-occurrence order; equal params share one declared local. */
    private static List<ArgBinding> distinctBindings(List<Predicate> predicates) {
        var seen = new LinkedHashSet<String>();
        var out = new ArrayList<ArgBinding>();
        for (var predicate : predicates) {
            switch (predicate) {
                case Predicate.Generated generated -> generated.terms().forEach(t -> {
                    if (seen.add(t.binding().localName())) out.add(t.binding());
                });
                case Predicate.Authored authored -> authored.bindings().forEach(b -> {
                    if (seen.add(b.localName())) out.add(b);
                });
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Reach: alias declarations and correlated EXISTS
    // ------------------------------------------------------------------------------------------

    /**
     * Declares one aliased jOOQ table local per hop of every reach path in the method, keyed by
     * the {@link ReachPath} instance's identity. Java locals are static per method scope
     * ({@code table_fkt<p>_<h>}); the SQL alias rides the base table's runtime name so two glue
     * calls in one query cannot collide. Identity, not value: locals are minted per reach
     * occurrence, so two structurally equal reaches on different terms keep their own aliases (see
     * {@link ReachPath}). {@code reachIndex} is this loop's own emission-scoped numbering and stays
     * here rather than riding the carrier.
     */
    private static Map<ReachPath, List<String>> declareReachAliases(
            MethodSpec.Builder builder, List<Predicate> predicates) {
        var aliases = new java.util.IdentityHashMap<ReachPath, List<String>>();
        int reachIndex = 0;
        for (var predicate : predicates) {
            var reaches = switch (predicate) {
                case Predicate.Generated generated ->
                    generated.terms().stream().map(ColumnTerm::reach).filter(r -> !r.isEmpty()).toList();
                case Predicate.Authored authored ->
                    authored.reach().isEmpty() ? List.<ReachPath>of() : List.of(authored.reach());
            };
            for (var reach : reaches) {
                var hopLocals = new ArrayList<String>(reach.size());
                for (int h = 0; h < reach.size(); h++) {
                    var target = reach.hop(h).targetTable();
                    String local = "table_fkt" + reachIndex + "_" + h;
                    builder.addStatement("$T $L = $T.$L.as(table.getName() + $S)",
                        target.tableClass(), local,
                        target.constantsClass(), target.javaFieldName(),
                        "_fkt" + reachIndex + "_" + h);
                    hopLocals.add(local);
                }
                aliases.put(reach, hopLocals);
                reachIndex++;
            }
        }
        return aliases;
    }

    /**
     * The correlated {@code EXISTS} every non-empty reach renders:
     * {@code DSL.exists(DSL.selectOne().from(terminal)<walk-back joins>.where(<correlation>
     * .and(<hop filters>).and(<inner>)))}. Shared by both arms; only the inner expression differs
     * (a column term against the terminal alias, or the developer call receiving it).
     *
     * <p>Every {@code On} arm is dispatched by {@link PathFragments}, hop by hop: the walk-back
     * joins through {@link PathFragments#emitBackwardBridging} and the hop-0 correlation through
     * {@link PathFragments#hopZeroCorrelation}. A hop's own {@code filter()} predicate (the
     * {@code {key:, condition:}} author form, which folds its condition onto the hop rather than
     * becoming the hop's {@code ON}) rides the same {@link PathFragments#appendHopFilters} call
     * the projection rail's scalar subselect makes; omitting it would emit a filter wider than the
     * schema declares.
     */
    static CodeBlock reachExists(ReachPath reach, List<String> hopAliases, CodeBlock inner) {
        var sel = CodeBlock.builder();
        sel.add("$T.selectOne()", DSL);
        sel.add("\n        .from($L)", hopAliases.get(hopAliases.size() - 1));
        for (int i = reach.size() - 1; i >= 1; i--) {
            sel.add("\n        $L", PathFragments.emitBackwardBridging(
                reach.hop(i), hopAliases.get(i - 1), hopAliases.get(i), "condition-reach"));
        }
        var where = CodeBlock.builder();
        where.add("$L", PathFragments.hopZeroCorrelation(reach.hop(0), hopAliases.get(0), "table"));
        PathFragments.appendHopFilters(where, reach.hops(), hopAliases, "table", ".and($L)");
        where.add(".and($L)", inner);
        sel.add("\n        .where($L)", where.build());
        return CodeBlock.of("$T.exists($L)", DSL, sel.build());
    }

    // ------------------------------------------------------------------------------------------
    // Terms and authored calls
    // ------------------------------------------------------------------------------------------

    private static CodeBlock termExpr(ColumnTerm term, Map<ReachPath, List<String>> aliasesByReach) {
        if (term.reach().isEmpty()) {
            return columnCompare(term, "table");
        }
        var hopAliases = aliasesByReach.get(term.reach());
        return reachExists(term.reach(), hopAliases,
            columnCompare(term, hopAliases.get(hopAliases.size() - 1)));
    }

    private static CodeBlock columnCompare(ColumnTerm term, String alias) {
        String local = term.binding().localName();
        if (term.columns().size() == 1) {
            String col = term.columns().get(0).javaName();
            return term.match() == MatchKind.EQUALITY
                ? CodeBlock.of("$L.$L.eq($T.val($L, $L.$L))", alias, col, DSL, local, alias, col)
                : CodeBlock.of("$L.$L.in($L)", alias, col, local);
        }
        var cells = CodeBlock.builder();
        for (int i = 0; i < term.columns().size(); i++) {
            if (i > 0) cells.add(", ");
            cells.add("$L.$L", alias, term.columns().get(i).javaName());
        }
        return term.match() == MatchKind.EQUALITY
            ? CodeBlock.of("$T.row($L).eq($L)", DSL, cells.build(), local)
            : CodeBlock.of("$T.row($L).in($L)", DSL, cells.build(), local);
    }

    /**
     * ANDs {@code expr} into the {@code condition} local under the term's presence guard: scalar
     * terms guard on {@code != null} unless the binding is proven non-null; list terms
     * additionally skip the empty list (an empty {@code IN ()} would render constant false and
     * zero the query rather than contributing no conjunct).
     *
     * <p>A pruning NodeId binding ({@link CallSiteExtraction.PruneOnMismatch}) forks off first: for
     * it, a {@code null} local can mean "the caller supplied an id this branch cannot decode", which
     * the presence guards above would render as an unfiltered branch. See
     * {@link #appendPruningAnd}.
     */
    private static void appendGuardedAnd(MethodSpec.Builder builder, ColumnTerm term, CodeBlock expr) {
        String local = term.binding().localName();
        boolean list = term.binding().param().list();
        if (decodeLeafOf(term.binding().param().extraction()) instanceof CallSiteExtraction.PruneOnMismatch) {
            appendPruningAnd(builder, term, expr);
            return;
        }
        if (list) {
            if (term.nonNull()) {
                builder.addStatement("if (!$L.isEmpty()) condition = condition.and($L)", local, expr);
            } else {
                builder.addStatement("if ($L != null && !$L.isEmpty()) condition = condition.and($L)",
                    local, local, expr);
            }
        } else {
            if (term.nonNull()) {
                builder.addStatement("condition = condition.and($L)", expr);
            } else {
                builder.addStatement("if ($L != null) condition = condition.and($L)", local, expr);
            }
        }
    }

    /**
     * The pruning fork of {@link #appendGuardedAnd}: a decode miss on this branch must render
     * {@code DSL.falseCondition()} (the branch structurally cannot match the supplied id), while an
     * absent filter must still render no conjunct. Three cells, because the local alone cannot always
     * tell the two apart:
     *
     * <ul>
     *   <li><b>Non-null scalar</b> ({@code id: ID!}): absent is unreachable, so a {@code null} local
     *       is a mismatch outright.</li>
     *   <li><b>List</b>: the prune-mode list helper returns {@code null} for an absent or empty wire
     *       list and a list otherwise, so a non-null <em>empty</em> return can only mean every element
     *       mismatched. An empty wire list keeps the shipped list-filter semantics (no conjunct),
     *       matching a single-table {@code @nodeId} list.</li>
     *   <li><b>Nullable scalar</b>: {@code null} conflates absent with mismatched and no sentinel
     *       exists in an arbitrary key type, so this cell guards on wire presence, the same args-map
     *       read the extraction expression already performs. A presence test, never a second
     *       decode.</li>
     * </ul>
     */
    private static void appendPruningAnd(MethodSpec.Builder builder, ColumnTerm term, CodeBlock expr) {
        String local = term.binding().localName();
        var param = term.binding().param();
        if (param.list()) {
            builder.addStatement("if ($L != null) condition = condition.and($L.isEmpty() ? $T.falseCondition() : $L)",
                local, local, DSL, expr);
            return;
        }
        if (term.nonNull()) {
            builder.addStatement("condition = condition.and($L != null ? $L : $T.falseCondition())",
                local, expr, DSL);
            return;
        }
        builder.addStatement("if ($L != null) condition = condition.and($L != null ? $L : $T.falseCondition())",
            pruningPresenceRead(param), local, expr, DSL);
    }

    /**
     * The wire-presence read for a nullable pruning scalar. Only a top-level argument reaches here: a
     * nested-input leaf whose participants diverge is rejected at classification time, so a pruning
     * binding is always rooted at the args map under its own argument name.
     */
    private static CodeBlock pruningPresenceRead(CallParam param) {
        if (!(param.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys)) {
            throw new IllegalStateException(
                "a pruning NodeId binding reached the glue renderer through a nested extraction ('"
                + param.name() + "'); a divergent nested @nodeId leaf rejects at classification time"
                + " and never lowers to a pruning branch filter");
        }
        return CodeBlock.of("args.get($S)", param.name());
    }

    private static CodeBlock authoredExpr(Predicate.Authored authored, Map<ReachPath, List<String>> aliasesByReach) {
        if (authored.reach().isEmpty()) {
            return authoredCall(authored.method(), "table", authored.bindings());
        }
        var hopAliases = aliasesByReach.get(authored.reach());
        return reachExists(authored.reach(), hopAliases,
            authoredCall(authored.method(), hopAliases.get(hopAliases.size() - 1), authored.bindings()));
    }

    private static CodeBlock authoredCall(AuthoredMethodRef method, String tableAlias, List<ArgBinding> bindings) {
        var args = CodeBlock.builder();
        args.add("$L", tableAlias);
        for (var binding : bindings) {
            args.add(", $L", binding.localName());
        }
        return CodeBlock.of("$T.$L($L)",
            ClassName.bestGuess(method.className()), method.methodName(), args.build());
    }

    // ------------------------------------------------------------------------------------------
    // Binding locals: declared type and extraction expression
    // ------------------------------------------------------------------------------------------

    /**
     * True when the binding's local declaration carries an unchecked cast, so the glue method
     * stamps {@code @SuppressWarnings("unchecked")}: a read off the args map casts to a
     * non-reifiable target ({@code Map.get} is statically {@code Object}), which covers list
     * reads ({@code List<X>}) and generically-typed authored parameters
     * ({@code Map<String, Object>}). The {@code JooqConvert} form is carved out (its
     * {@code instanceof} pattern plus {@code DSL.val} coercion own the runtime shape without a
     * cast), as is a NodeId decode (the helper takes {@code Object}).
     */
    private static boolean emitsUncheckedLocalCast(CallParam param) {
        return switch (param.extraction()) {
            case CallSiteExtraction.Direct ignored -> localType(param) instanceof ParameterizedTypeName;
            case CallSiteExtraction.ContextArg ignored -> localType(param) instanceof ParameterizedTypeName;
            case CallSiteExtraction.NestedInputField nif ->
                !(nif.leaf() instanceof CallSiteExtraction.JooqConvert)
                    && !(nif.leaf() instanceof CallSiteExtraction.NodeIdDecodeKeys)
                    && localType(param) instanceof ParameterizedTypeName;
            default -> false;
        };
    }

    /**
     * The binding local's declared type: the decode shape for NodeId bindings, otherwise the
     * param's structured Java type in full (authored params carry generics, e.g.
     * {@code Map<String, Object>}, and a raw-typed local would trip the consumer's
     * {@code -Xlint:rawtypes -Werror} compile), with the list axis wrapping the element type
     * exactly as the retired entity signatures did.
     */
    private static TypeName localType(CallParam param) {
        var decode = decodeLeafOf(param.extraction());
        if (decode != null) {
            return CompositeDecodeHelperRegistry.decodedType(decode.decodeMethod(), decodesList(param));
        }
        if (!param.list() && param.javaType() instanceof ParameterizedTypeName full) {
            return full;
        }
        ClassName raw = ClassName.bestGuess(WireMapChain.rawComponent(param.typeName()));
        return param.list() ? ParameterizedTypeName.get(LIST, raw) : raw;
    }

    /**
     * Whether a decoding binding's local is list-shaped. A generated column term carries the axis on
     * {@link CallParam#list()}; an authored condition parameter does not, its {@code CallParam} being
     * minted from a reflected signature where list-ness lives in the declared Java type. Reading both
     * is what lets one decode arm serve the implicit predicate and the authored call on one slot.
     *
     * <p>Sound rather than a guess: an authored parameter bound to a {@code @nodeId} slot is admitted
     * at classification only when its declared type is exactly the decoded key's
     * ({@code ConditionResolver.installNodeIdDecode}), so it is a {@code List<…>} precisely where the
     * slot is list-shaped.
     */
    private static boolean decodesList(CallParam param) {
        return param.list()
            || (param.javaType() instanceof ParameterizedTypeName parameterized
                && parameterized.rawType().equals(LIST));
    }

    private static CallSiteExtraction.NodeIdDecodeKeys decodeLeafOf(CallSiteExtraction extraction) {
        if (extraction instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
            return nidk;
        }
        if (extraction instanceof CallSiteExtraction.NestedInputField nif
            && nif.leaf() instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
            return nidk;
        }
        return null;
    }

    private static CodeBlock extractionExpr(CallParam param, Map<String, String> liftLocals,
            CompositeDecodeHelperRegistry registry, RequestContextHelper contextHelper,
            ProjectedKeyReads keys) {
        return switch (param.extraction()) {
            case CallSiteExtraction.Direct ignored ->
                CodeBlock.of("($T) args.get($S)", localType(param), param.name());
            case CallSiteExtraction.EnumValueOf ev -> {
                var enumClass = ClassName.bestGuess(ev.enumClassName());
                yield CodeBlock.of("args.get($S) != null ? $T.valueOf(($T) args.get($S)) : null",
                    param.name(), enumClass, String.class, param.name());
            }
            case CallSiteExtraction.JooqConvert jc -> param.list()
                ? CodeBlock.of("args.get($S) instanceof $T<?> keys"
                        + " ? keys.stream().map(k -> $T.val(k, table.$L.getDataType()).getValue()).toList() : null",
                    param.name(), LIST, DSL, jc.columnJavaName())
                : CodeBlock.of("$T.val(args.get($S), table.$L.getDataType()).getValue()",
                    DSL, param.name(), jc.columnJavaName());
            case CallSiteExtraction.NodeIdDecodeKeys nidk ->
                decodeCall(registry, nidk, decodesList(param), CodeBlock.of("args.get($S)", param.name()));
            case CallSiteExtraction.NestedInputField nif ->
                nestedExtraction(nif, param, liftLocals, registry, keys);
            // Request context is not in the args map: the local reads through the class's own
            // graphitronContext(env) helper, whose need the collector records so the drain and
            // the call cannot separate (the shipped-twice missing-helper bug class).
            case CallSiteExtraction.ContextArg ignored ->
                CodeBlock.of("($T) $L.getContextArgument(env, $S)",
                    localType(param), contextHelper.call(), param.name());
            case CallSiteExtraction.InputBean ignored -> throw new IllegalStateException(
                "InputBean is a @service parameter concept and never a condition binding");
            case CallSiteExtraction.JooqRecord ignored -> throw new IllegalStateException(
                "JooqRecord is a @service parameter concept and never a condition binding");
            case CallSiteExtraction.NodeIdDecodeRecord ignored -> throw new IllegalStateException(
                "NodeIdDecodeRecord is an input-bean field leaf only and never a condition binding");
        };
    }

    /**
     * The decode helper call for one NodeId binding. Exhaustive over the seal rather than a
     * two-valued test, so a third failure mode has to state its mode here instead of silently
     * inheriting a neighbour's.
     */
    private static CodeBlock decodeCall(CompositeDecodeHelperRegistry registry,
            CallSiteExtraction.NodeIdDecodeKeys nidk, boolean list, CodeBlock wireExpr) {
        var mode = switch (nidk) {
            case CallSiteExtraction.ThrowOnMismatch ignored -> CompositeDecodeHelperRegistry.Mode.THROW;
            case CallSiteExtraction.PruneOnMismatch ignored -> CompositeDecodeHelperRegistry.Mode.SKIP;
        };
        return CodeBlock.of("$L($L)", registry.register(nidk.decodeMethod(), mode, list), wireExpr);
    }

    /**
     * The nested-Map traversal on a binding local's right-hand side: a chain of
     * {@code instanceof Map<?, ?>} ternaries from the outer argument (or its lifted local) down
     * to the leaf, {@code null} at any absent level. The leaf applies the same special cases the
     * call-site emitter applies today: a NodeId decode hands the uncast traversal to its helper,
     * a converter-backed leaf coerces through the column's {@code DataType}, and everything else
     * casts to the declared type.
     */
    private static CodeBlock nestedExtraction(CallSiteExtraction.NestedInputField nif, CallParam param,
            Map<String, String> liftLocals, CompositeDecodeHelperRegistry registry,
            ProjectedKeyReads keys) {
        if (nif.leaf() instanceof CallSiteExtraction.ContextArg) {
            // The resolver keeps context params bare when it rewraps a nested condition's value
            // params (ConditionResolver.rewrapForNested), so this leaf shape is unconstructable;
            // a generic Map-traversal cast here would silently read the wrong surface.
            throw new IllegalStateException(
                "a nested extraction with a request-context leaf reached the glue renderer; "
                + "context params stay bare ContextArg bindings and never traverse the args map");
        }
        String lifted = liftLocals.get(nif.outerArgName());
        CodeBlock root = lifted != null
            ? CodeBlock.of("$L", lifted)
            : CodeBlock.of("args.get($S)", nif.outerArgName());

        // Row presence decides, ahead of every leaf special case: a path whose last segment names a key
        // column of a @nodeId's node type reads that column off a decoded record, and the wire value it
        // decodes sits one segment short of the path's end. The leaf the walk resolved is an unresolvable
        // one (the path descends past a scalar), so it arrives here as Direct and none of the arms below
        // would know the difference.
        String written = nif.outerArgName() + "." + String.join(".", nif.path());
        var projected = keys.readFor(written, written.substring(0, written.lastIndexOf('.')),
            () -> nif.path().size() == 1
                ? CodeBlock.of("args.get($S)", nif.outerArgName())
                : WireMapChain.of(root, nif.path().subList(0, nif.path().size() - 1), null, lifted));
        if (projected.isPresent()) {
            return projected.get();
        }

        if (nif.leaf() instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
            return decodeCall(registry, nidk, decodesList(param),
                WireMapChain.of(root, nif.path(), null, lifted));
        }
        if (nif.leaf() instanceof CallSiteExtraction.EnumValueOf ev) {
            // The nested twin of the top-level enum arm. Reachable since the rewrap composes the
            // parameter's own extraction onto the descent rather than defaulting the leaf: before
            // that, an enum-typed parameter at a nested input-field condition arrived as Direct and
            // the fall-through below cast a wire String to the enum type.
            var enumClass = ClassName.bestGuess(ev.enumClassName());
            return CodeBlock.of("($L) instanceof $T enumWire ? $T.valueOf(enumWire) : null",
                WireMapChain.of(root, nif.path(), null, lifted), String.class, enumClass);
        }
        if (nif.leaf() instanceof CallSiteExtraction.JooqConvert jc) {
            CodeBlock chain = WireMapChain.of(root, nif.path(), null, lifted);
            if (param.list()) {
                return CodeBlock.of(
                    "($L) instanceof $T<?> keys ? keys.stream().map(k -> $T.val(k, table.$L.getDataType()).getValue()).toList() : null",
                    chain, LIST, DSL, jc.columnJavaName());
            }
            return CodeBlock.of("$T.val($L, table.$L.getDataType()).getValue()", DSL, chain, jc.columnJavaName());
        }
        return WireMapChain.of(root, nif.path(), localType(param), lifted);
    }

}
