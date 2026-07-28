package no.sikt.graphitron.render;

import no.sikt.graphitron.command.ArgBinding;
import no.sikt.graphitron.command.ColumnTerm;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.FkHop;
import no.sikt.graphitron.command.MatchKind;
import no.sikt.graphitron.command.OuterLift;
import no.sikt.graphitron.command.Predicate;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.MethodRef;

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
 * predicates as calls into developer code. All value reads root at the {@code args} map
 * parameter; callers supply {@code env.getArguments()} or {@code <sf>.getArguments()}, which are
 * the same coerced map ({@code DataFetchingEnvironment.getArgument} is
 * {@code getArguments().get}).
 *
 * <p>Reach renders as a correlated {@code EXISTS} over the row's proven FK hops. SQL aliases are
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

    /** Renders one conditions class per distinct glue owner among {@code rows}, in row order. */
    public static List<TypeSpec> render(List<ConditionCommand> rows, String outputPackage) {
        var byOwner = new LinkedHashMap<UnitRef, List<ConditionCommand>>();
        for (var row : rows) {
            byOwner.computeIfAbsent(row.glue().owner(), k -> new ArrayList<>()).add(row);
        }
        var out = new ArrayList<TypeSpec>(byOwner.size());
        for (var entry : byOwner.entrySet()) {
            var classBuilder = TypeSpec.classBuilder(entry.getKey().simpleName()).addModifiers(Modifier.PUBLIC);
            CompositeDecodeHelperRegistry.collectInto(classBuilder, outputPackage, registry -> {
                for (var row : entry.getValue()) {
                    classBuilder.addMethod(buildGlueMethod(
                        row.glue().methodName(), row.table().tableClass(),
                        row.predicates(), row.lifts(), registry));
                    for (var fragment : row.facets()) {
                        classBuilder.addMethod(buildGlueMethod(
                            fragment.method().methodName(), row.table().tableClass(),
                            fragment.predicates(), fragment.lifts(), registry));
                    }
                }
            });
            out.add(classBuilder.build());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // One glue method
    // ------------------------------------------------------------------------------------------

    private static MethodSpec buildGlueMethod(String methodName, TypeName jooqTableClass,
            List<Predicate> predicates, List<OuterLift> lifts, CompositeDecodeHelperRegistry registry) {
        var builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(jooqTableClass, "table")
            .addParameter(ARGS_MAP, "args");

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
        for (var binding : bindings) {
            builder.addStatement("$T $L = $L",
                localType(binding.param()), binding.localName(),
                extractionExpr(binding.param(), liftLocals, registry));
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
     * the reach list's identity. Java locals are static per method scope
     * ({@code table_fkt<p>_<h>}); the SQL alias rides the base table's runtime name so two glue
     * calls in one query cannot collide.
     */
    private static Map<List<FkHop>, List<String>> declareReachAliases(
            MethodSpec.Builder builder, List<Predicate> predicates) {
        var aliases = new java.util.IdentityHashMap<List<FkHop>, List<String>>();
        int reachIndex = 0;
        for (var predicate : predicates) {
            var reaches = switch (predicate) {
                case Predicate.Generated generated ->
                    generated.terms().stream().map(ColumnTerm::reach).filter(r -> !r.isEmpty()).toList();
                case Predicate.Authored authored ->
                    authored.reach().isEmpty() ? List.<List<FkHop>>of() : List.of(authored.reach());
            };
            for (var reach : reaches) {
                var hopLocals = new ArrayList<String>(reach.size());
                for (int h = 0; h < reach.size(); h++) {
                    var target = reach.get(h).hop().targetTable();
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
     * {@code DSL.exists(DSL.selectOne().from(terminal)<walk-back joins>.where(<correlation>.and(<inner>)))}.
     * Shared by both arms; only the inner expression differs (a column term against the terminal
     * alias, or the developer call receiving it).
     */
    private static CodeBlock reachExists(List<FkHop> reach, List<String> hopAliases, CodeBlock inner) {
        var sel = CodeBlock.builder();
        sel.add("$T.selectOne()", DSL);
        sel.add("\n        .from($L)", hopAliases.get(hopAliases.size() - 1));
        for (int i = reach.size() - 1; i >= 1; i--) {
            sel.add("\n        $L",
                JoinFragments.emitBridgingJoin(reach.get(i).pairs(), hopAliases.get(i - 1), hopAliases.get(i)));
        }
        var correlation = JoinFragments.emitCorrelationWhere(reach.get(0).pairs(), hopAliases.get(0), "table");
        sel.add("\n        .where($L.and($L))", correlation, inner);
        return CodeBlock.of("$T.exists($L)", DSL, sel.build());
    }

    // ------------------------------------------------------------------------------------------
    // Terms and authored calls
    // ------------------------------------------------------------------------------------------

    private static CodeBlock termExpr(ColumnTerm term, Map<List<FkHop>, List<String>> aliasesByReach) {
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
     */
    private static void appendGuardedAnd(MethodSpec.Builder builder, ColumnTerm term, CodeBlock expr) {
        String local = term.binding().localName();
        boolean list = term.binding().param().list();
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

    private static CodeBlock authoredExpr(Predicate.Authored authored, Map<List<FkHop>, List<String>> aliasesByReach) {
        if (authored.reach().isEmpty()) {
            return authoredCall(authored.method(), "table", authored.bindings());
        }
        var hopAliases = aliasesByReach.get(authored.reach());
        return reachExists(authored.reach(), hopAliases,
            authoredCall(authored.method(), hopAliases.get(hopAliases.size() - 1), authored.bindings()));
    }

    private static CodeBlock authoredCall(MethodRef method, String tableAlias, List<ArgBinding> bindings) {
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
            return CompositeDecodeHelperRegistry.decodedType(decode.decodeMethod(), param.list());
        }
        if (!param.list() && param.javaType() instanceof ParameterizedTypeName full) {
            return full;
        }
        ClassName raw = ClassName.bestGuess(rawComponent(param.typeName()));
        return param.list() ? ParameterizedTypeName.get(LIST, raw) : raw;
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
            CompositeDecodeHelperRegistry registry) {
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
                decodeCall(registry, nidk, param.list(), CodeBlock.of("args.get($S)", param.name()));
            case CallSiteExtraction.NestedInputField nif ->
                nestedExtraction(nif, param, liftLocals, registry);
            case CallSiteExtraction.ContextArg ignored -> throw new IllegalStateException(
                "a context-bound condition argument reached the glue renderer; the validator's"
                + " env-bound rejection must reject committed rows whose bindings need the request env");
            case CallSiteExtraction.InputBean ignored -> throw new IllegalStateException(
                "InputBean is a @service parameter concept and never a condition binding");
            case CallSiteExtraction.JooqRecord ignored -> throw new IllegalStateException(
                "JooqRecord is a @service parameter concept and never a condition binding");
            case CallSiteExtraction.NodeIdDecodeRecord ignored -> throw new IllegalStateException(
                "NodeIdDecodeRecord is an input-bean field leaf only and never a condition binding");
        };
    }

    private static CodeBlock decodeCall(CompositeDecodeHelperRegistry registry,
            CallSiteExtraction.NodeIdDecodeKeys nidk, boolean list, CodeBlock wireExpr) {
        var mode = nidk instanceof CallSiteExtraction.ThrowOnMismatch
            ? CompositeDecodeHelperRegistry.Mode.THROW
            : CompositeDecodeHelperRegistry.Mode.SKIP;
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
            Map<String, String> liftLocals, CompositeDecodeHelperRegistry registry) {
        String lifted = liftLocals.get(nif.outerArgName());
        CodeBlock root = lifted != null
            ? CodeBlock.of("$L", lifted)
            : CodeBlock.of("args.get($S)", nif.outerArgName());

        if (nif.leaf() instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
            return decodeCall(registry, nidk, param.list(),
                mapChain(root, nif.path(), 0, null, lifted));
        }
        if (nif.leaf() instanceof CallSiteExtraction.JooqConvert jc) {
            CodeBlock chain = mapChain(root, nif.path(), 0, null, lifted);
            if (param.list()) {
                return CodeBlock.of(
                    "($L) instanceof $T<?> keys ? keys.stream().map(k -> $T.val(k, table.$L.getDataType()).getValue()).toList() : null",
                    chain, LIST, DSL, jc.columnJavaName());
            }
            return CodeBlock.of("$T.val($L, table.$L.getDataType()).getValue()", DSL, chain, jc.columnJavaName());
        }
        return mapChain(root, nif.path(), 0, localType(param), lifted);
    }

    /**
     * Builds the depth-{@code depth} step's ternary, recursing inward. A lifted head skips the
     * {@code instanceof Map<?, ?>} rebind in favour of a {@code != null} guard on the lifted
     * local; inner steps rebind via {@code map2, map3, ...}. A {@code null} {@code leafType}
     * means "do not cast the Map.get result" (the decode and converter leaves own the runtime
     * shape).
     */
    private static CodeBlock mapChain(CodeBlock currentExpr, List<String> path, int depth,
            TypeName leafType, String topBinding) {
        String key = path.get(depth);
        boolean isLeaf = depth == path.size() - 1;
        boolean liftedHead = topBinding != null && depth == 0;
        String binding = liftedHead ? topBinding : "map" + (depth + 1);

        if (isLeaf) {
            if (liftedHead) {
                return leafType == null
                    ? CodeBlock.of("$L != null ? $L.get($S) : null", binding, binding, key)
                    : CodeBlock.of("$L != null ? ($T) $L.get($S) : null", binding, leafType, binding, key);
            }
            return leafType == null
                ? CodeBlock.of("$L instanceof $T<?, ?> $L ? $L.get($S) : null",
                    currentExpr, MAP, binding, binding, key)
                : CodeBlock.of("$L instanceof $T<?, ?> $L ? ($T) $L.get($S) : null",
                    currentExpr, MAP, binding, leafType, binding, key);
        }
        CodeBlock next = CodeBlock.of("$L.get($S)", binding, key);
        if (liftedHead) {
            return CodeBlock.of("$L != null ? ($L) : null",
                binding, mapChain(next, path, depth + 1, leafType, null));
        }
        return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($L) : null",
            currentExpr, MAP, binding, mapChain(next, path, depth + 1, leafType, null));
    }

    private static String rawComponent(String typeName) {
        int lt = typeName.indexOf('<');
        return lt < 0 ? typeName : typeName.substring(0, lt);
    }
}
