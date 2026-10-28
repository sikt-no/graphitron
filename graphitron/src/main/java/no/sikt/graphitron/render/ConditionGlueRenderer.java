package no.sikt.graphitron.render;

import no.sikt.graphitron.command.ArgBinding;
import no.sikt.graphitron.command.AuthoredMethodRef;
import no.sikt.graphitron.command.ColumnTerm;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.MatchKind;
import no.sikt.graphitron.command.OuterLift;
import no.sikt.graphitron.command.Predicate;
import no.sikt.graphitron.command.PresenceGuard;
import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.command.UnitRef;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.command.SessionAccessors;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * rather than off the args map. A parameter bound to the whole of such a slot is the install rail's
     * instead, and takes precedence where both resolve; see
     * {@link ProjectedKeyReads#installRailOwns}. The decode body is hosted on this class, which is the
     * reason this site needs more than the routine site did: the {@code decode<TypeName>Record} bodies a
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
                InputBeanHelperRegistry.collectInto(classBuilder, outputPackage, decodes, beans ->
                ElementRowsHelperRegistry.collectInto(classBuilder, elementRows ->
                RequestContextHelper.collectInto(classBuilder, outputPackage, contextHelper -> {
                    // One projection host per class: the relation is the graph's, and reaching a decode
                    // registers its body here, so a called helper cannot go un-emitted.
                    var keyHost = new ProjectedKeyHost(keyProjections,
                        projection -> decodes.register(NodeIdEncoderRef.of(outputPackage),
                            projection.typeId(), projection.nodeTypeName(),
                            projection.keyColumns(), projection.nodeTable()));
                    var hosts = new Hosts(registry, contextHelper, beans, elementRows);
                    for (var row : entry.getValue()) {
                        boolean takesEnv = row.readsRequestContext();
                        boolean takesDsl = row.readsSession();
                        String coordinate = row.coordinate().getTypeName() + "." + row.coordinate().getFieldName();
                        classBuilder.addMethod(buildGlueMethod(
                            row.glue().methodName(), CatalogRefs.tableClass(row.table()),
                            row.predicates(), row.lifts(), takesEnv, takesDsl, coordinate, outputPackage, hosts,
                            keyHost.at(row.coordinate())));
                        for (var fragment : row.facets()) {
                            classBuilder.addMethod(buildGlueMethod(
                                fragment.method().methodName(), CatalogRefs.tableClass(row.table()),
                                fragment.predicates(), fragment.lifts(), takesEnv, takesDsl, coordinate,
                                outputPackage, hosts,
                                keyHost.at(row.coordinate())));
                        }
                    }
                })))));
            out.add(classBuilder.build());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // One glue method
    // ------------------------------------------------------------------------------------------

    /**
     * The class-scoped helper hosts a glue method's binding locals register with: the node-id key
     * decodes, the request-context reader, the input-bean builders, and the list-of-input row
     * reads. One value per conditions class, so every glue method on it registers with the same
     * four.
     */
    private record Hosts(CompositeDecodeHelperRegistry decodes, RequestContextHelper contextHelper,
                         InputBeanHelperRegistry beans, ElementRowsHelperRegistry elementRows) {}

    private static MethodSpec buildGlueMethod(String methodName, TypeName jooqTableClass,
            List<Predicate> predicates, List<OuterLift> lifts, boolean takesEnv, boolean takesDsl,
            String coordinate, String outputPackage, Hosts hosts, ProjectedKeyReads keys) {
        var builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(jooqTableClass, "table")
            .addParameter(ARGS_MAP, "args");
        if (takesEnv) {
            builder.addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env");
        }
        if (takesDsl) {
            builder.addParameter(ClassName.get("org.jooq", "DSLContext"), "dsl");
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
        // Ahead of the binding locals: a partial list-of-input local compares against its term's
        // own table, which for a reached term is the terminal hop alias. Aliases read only table.
        var aliasesByReach = declareReachAliases(builder, predicates);
        var partialTables = partialMatchTables(predicates, aliasesByReach);

        // Composed before it is emitted: a projected binding registers a node-id decode with the
        // sink, and that materialisation has to be declared ahead of every local reading it. With no
        // projection the sink contributes nothing and this emits exactly what the loop used to.
        var bindingLocals = new ArrayList<CodeBlock>();
        for (var binding : bindings) {
            var valueTable = ValueTable.of(binding, jooqTableClass);
            String matchTable = partialTables.get(binding.localName());
            if (matchTable != null) {
                valueTable = new ValueTable(valueTable.tableClass(), CodeBlock.of("$L", matchTable));
            }
            bindingLocals.add(extractionExpr(binding.param(), valueTable, liftLocals, hosts, keys,
                coordinate, outputPackage));
        }
        builder.addCode(keys.declarations());
        for (int i = 0; i < bindings.size(); i++) {
            builder.addStatement("$T $L = $L",
                localType(bindings.get(i).param()), bindings.get(i).localName(), bindingLocals.get(i));
        }

        builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
        for (var predicate : predicates) {
            switch (predicate) {
                case Predicate.Generated generated -> {
                    for (var term : generated.terms()) {
                        appendGuardedAnd(builder, term, termExpr(term, aliasesByReach));
                    }
                }
                case Predicate.Authored authored ->
                    appendAuthoredAnd(builder, authored, liftLocals, aliasesByReach);
            }
        }
        builder.addStatement("return condition");
        return builder.build();
    }

    /**
     * The method's binding set in first-occurrence order; equal params share one declared local.
     * A shared local takes the reached table of whichever occurrence names one: an authored
     * predicate's binding never knows a generated term's reach, and the value both read is the
     * same column's.
     */
    private static List<ArgBinding> distinctBindings(List<Predicate> predicates) {
        var byLocal = new LinkedHashMap<String, ArgBinding>();
        java.util.function.Consumer<ArgBinding> add = b -> byLocal.merge(b.localName(), b, (kept, next) -> {
            if (kept.reachedTable().isPresent() && next.reachedTable().isPresent()
                    && !kept.reachedTable().equals(next.reachedTable())) {
                throw new IllegalStateException("glue local '" + kept.localName() + "' is read off "
                    + kept.reachedTable().get().tableName() + " and " + next.reachedTable().get().tableName()
                    + "; one call param binds one column");
            }
            return kept.reachedTable().isPresent() ? kept : next.reachedTable().isPresent() ? next : kept;
        });
        for (var predicate : predicates) {
            switch (predicate) {
                case Predicate.Generated generated -> generated.terms().forEach(t -> add.accept(t.binding()));
                case Predicate.Authored authored -> authored.bindings().forEach(add);
            }
        }
        return List.copyOf(byLocal.values());
    }

    /**
     * The table each partial list-of-input binding's match helper compares against, keyed by the
     * binding's local: {@code table} for an own-table term, the terminal hop alias of the term's own
     * reach for a reached one. The binding knows its reached table but not which reach occurrence
     * its term owns, so the term that mints it resolves the alias. A partial local is the finished
     * condition of one term, so a second occurrence of it is a producer bug: an authored
     * {@code @condition} on the list field binds the raw list under its own extraction.
     */
    private static Map<String, String> partialMatchTables(List<Predicate> predicates,
            Map<ReachPath, List<String>> aliasesByReach) {
        var tables = new LinkedHashMap<String, String>();
        var occurrences = new ArrayList<ArgBinding>();
        for (var predicate : predicates) {
            switch (predicate) {
                case Predicate.Generated generated -> {
                    for (var term : generated.terms()) {
                        if (!isPartial(term.binding())) {
                            continue;
                        }
                        occurrences.add(term.binding());
                        var hopAliases = aliasesByReach.get(term.reach());
                        tables.put(term.binding().localName(),
                            term.reach().isEmpty() ? "table" : hopAliases.get(hopAliases.size() - 1));
                    }
                }
                case Predicate.Authored authored ->
                    authored.bindings().stream().filter(ConditionGlueRenderer::isPartial).forEach(occurrences::add);
            }
        }
        if (occurrences.size() != tables.size()
                || occurrences.stream().anyMatch(b -> !tables.containsKey(b.localName()))) {
            throw new IllegalStateException("a partial list-of-input local is read by more than one"
                + " occurrence (" + occurrences.stream().map(ArgBinding::localName).toList()
                + "); it is the finished condition of one generated term");
        }
        return tables;
    }

    private static boolean isPartial(ArgBinding binding) {
        return CallSiteExtraction.ElementRows.of(binding.param().extraction())
            instanceof CallSiteExtraction.ElementRows.Partial;
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
                        CatalogRefs.tableClass(target), local,
                        CatalogRefs.constantsClass(target), target.javaFieldName(),
                        "_fkt" + reachIndex + "_" + h);
                    hopLocals.add(local);
                }
                aliases.put(reach, hopLocals);
                reachIndex++;
            }
        }
        return aliases;
    }

    // ------------------------------------------------------------------------------------------
    // Terms and authored calls
    // ------------------------------------------------------------------------------------------

    private static CodeBlock termExpr(ColumnTerm term, Map<ReachPath, List<String>> aliasesByReach) {
        if (term.reach().isEmpty()) {
            return columnCompare(term, "table");
        }
        var hopAliases = aliasesByReach.get(term.reach());
        return PathFragments.reachExists(term.reach(), hopAliases, "table",
            columnCompare(term, hopAliases.get(hopAliases.size() - 1)));
    }

    /**
     * The four shapes a column term lowers to, as {@code receiver.op(local)} over shape times
     * arity: single-column {@code eq} / {@code in}, and the row-value forms of both. A partial
     * membership term renders its local itself: the match helper already compared each carried
     * cell against {@code alias}'s columns, so the local is the finished condition.
     *
     * <p>No shape spells a bind. jOOQ types every comparison operand at the receiving column's
     * {@code DataType} ({@code Field.eq} and {@code Field.in} route each value through
     * {@code DSL.val(value, field)}, and {@code Row.eq} / {@code Row.in} compare cell to cell), so
     * a registered {@code Converter} on the receiver runs in all four. That is
     * {@link ColumnComparison}'s companion bind rule already satisfied: for a generated filter the
     * receiver <em>is</em> the column the value was read from. It holds only because the binding
     * local is declared at the receiving column's own Java type, which is the guarantee
     * {@link no.sikt.graphitron.rewrite.model.BodyParam.ColumnPredicate} records.
     */
    private static CodeBlock columnCompare(ColumnTerm term, String alias) {
        String local = term.binding().localName();
        if (term.match() == MatchKind.PARTIAL_MEMBERSHIP) {
            return CodeBlock.of("$L", local);
        }
        if (term.columns().size() == 1) {
            String col = term.columns().get(0).javaName();
            return term.match() == MatchKind.EQUALITY
                ? CodeBlock.of("$L.$L.eq($L)", alias, col, local)
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
     * zero the query rather than contributing no conjunct). A partial membership term guards as a
     * scalar whatever its nullability, since its match helper answers {@code null} for an absent or
     * empty list; the guard sits outside a reached term's {@code EXISTS}.
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
        if (term.match() == MatchKind.PARTIAL_MEMBERSHIP) {
            builder.addStatement("if ($L != null) condition = condition.and($L)", local, expr);
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

    /**
     * ANDs an authored predicate's call into the {@code condition} local under the guard the
     * producer put on the row. {@link PresenceGuard.Always} emits the bare statement: nothing of
     * ours sits between the method's return value and the WHERE clause, so the author owns the
     * semantics. {@link PresenceGuard.FieldPresent} wraps the statement in a wire-presence test on
     * the field that owns the correlated {@code EXISTS}, so an omitted filter contributes no
     * conjunct and the method is not called.
     *
     * <p>The read is a presence test, never a second decode: the same args-map descent the binding
     * locals take, stopped one step short of decoding, which is why a value-less authored signature
     * guards exactly as well as one that binds the value.
     */
    private static void appendAuthoredAnd(MethodSpec.Builder builder, Predicate.Authored authored,
            Map<String, String> liftLocals, Map<ReachPath, List<String>> aliasesByReach) {
        var expr = authoredExpr(authored, aliasesByReach);
        switch (authored.presence()) {
            case PresenceGuard.Always ignored -> builder.addStatement("condition = condition.and($L)", expr);
            case PresenceGuard.FieldPresent guard -> {
                var read = fieldPresenceRead(guard, liftLocals);
                if (guard.list()) {
                    builder.addStatement(
                        "if ($L instanceof $T<?> presentList && !presentList.isEmpty()) condition = condition.and($L)",
                        read, LIST, expr);
                } else {
                    builder.addStatement("if ($L != null) condition = condition.and($L)", read, expr);
                }
            }
        }
    }

    /**
     * The undecoded wire read at a filter field's address: the nested-map descent from the owning
     * argument down to the field, riding the argument's lifted local when the method has one. The
     * top-level shape is the read {@link #pruningPresenceRead} emits, one segment deeper.
     */
    private static CodeBlock fieldPresenceRead(PresenceGuard.FieldPresent guard,
            Map<String, String> liftLocals) {
        String lifted = liftLocals.get(guard.outerArgName());
        CodeBlock root = lifted != null
            ? CodeBlock.of("$L", lifted)
            : CodeBlock.of("args.get($S)", guard.outerArgName());
        return WireMapChain.of(root, guard.path(), null, lifted);
    }

    private static CodeBlock authoredExpr(Predicate.Authored authored, Map<ReachPath, List<String>> aliasesByReach) {
        if (authored.reach().isEmpty()) {
            return authoredCall(authored.method(), "table", authored.bindings());
        }
        var hopAliases = aliasesByReach.get(authored.reach());
        return PathFragments.reachExists(authored.reach(), hopAliases, "table",
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
     * cast), as are a NodeId decode, an input bean and a list-of-input row read (each helper takes
     * the uncast wire value).
     */
    private static boolean emitsUncheckedLocalCast(CallParam param) {
        return switch (param.extraction()) {
            case CallSiteExtraction.Direct ignored -> localType(param) instanceof ParameterizedTypeName;
            case CallSiteExtraction.ContextArg ignored -> localType(param) instanceof ParameterizedTypeName;
            case CallSiteExtraction.NestedInputField nif ->
                !(nif.leaf() instanceof CallSiteExtraction.JooqConvert)
                    && !(nif.leaf() instanceof CallSiteExtraction.NodeIdDecodeKeys)
                    && !(nif.leaf() instanceof CallSiteExtraction.InputBean)
                    && !(nif.leaf() instanceof CallSiteExtraction.ElementRows)
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
     *
     * <p>A list-of-input element read takes its type from its arm and never from
     * {@link CallParam#typeName()}: a whole read derives it from its cells' columns, since the
     * row-shaped call type is the bare {@code org.jooq.Row<N>} string and falling through would
     * declare a raw {@code List<Row2>}, which a consumer compiling under
     * {@code -Xlint:rawtypes -Werror} rejects; a partial read is the finished {@code Condition}.
     */
    private static TypeName localType(CallParam param) {
        if (param.readsSession()) {
            return param.javaType();
        }
        var decode = decodeLeafOf(param.extraction());
        if (decode != null) {
            return CompositeDecodeHelperRegistry.decodedType(decode.decodeMethod(), decodesList(param));
        }
        var elementRows = CallSiteExtraction.ElementRows.of(param.extraction());
        if (elementRows != null) {
            return ElementRowsHelperRegistry.localType(elementRows);
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

    /**
     * The table a binding's values are lifted against: its {@link ArgBinding#reachedTable()} when the
     * term reaches one, read through the unaliased catalog constant, otherwise the glue's own
     * {@code table}. An unaliased constant is sound because every read through it takes only a
     * column's {@code DataType}, which no alias changes.
     */
    private record ValueTable(TypeName tableClass, CodeBlock expr) {
        static ValueTable of(ArgBinding binding, TypeName jooqTableClass) {
            return binding.reachedTable()
                .map(t -> new ValueTable(CatalogRefs.tableClass(t),
                    CodeBlock.of("$T.$L", CatalogRefs.constantsClass(t), t.javaFieldName())))
                .orElseGet(() -> new ValueTable(jooqTableClass, CodeBlock.of("table")));
        }
    }

    private static CodeBlock extractionExpr(CallParam param, ValueTable valueTable,
            Map<String, String> liftLocals, Hosts hosts, ProjectedKeyReads keys,
            String coordinate, String outputPackage) {
        return switch (param.extraction()) {
            // The session handle (or one member of it) rides the executing DSLContext's own
            // configuration data, so the local reads through the connection runtime's guarded
            // accessor off the glue's dsl parameter: per pinned connection, and a located throw
            // rather than a silent null where nothing was mounted.
            case CallSiteExtraction.SessionHandleRead ignored ->
                CodeBlock.of("$T.sessionHandle(dsl, $S)",
                    ClassName.get(outputPackage + ".schema", "TenantConnections"), coordinate);
            case CallSiteExtraction.SessionMemberRead member ->
                CodeBlock.of("$T.$L(dsl, $S)", ClassName.get(outputPackage + ".schema", "TenantConnections"),
                    SessionAccessors.memberMethod(member.member()), coordinate);
            case CallSiteExtraction.Direct ignored ->
                CodeBlock.of("($T) args.get($S)", localType(param), param.name());
            case CallSiteExtraction.EnumValueOf ev -> {
                var enumClass = ClassName.bestGuess(ev.enumClassName());
                yield CodeBlock.of("args.get($S) != null ? $T.valueOf(($T) args.get($S)) : null",
                    param.name(), enumClass, String.class, param.name());
            }
            case CallSiteExtraction.JooqConvert jc -> param.list()
                ? CodeBlock.of("args.get($S) instanceof $T<?> keys"
                        + " ? keys.stream().map(k -> $T.val(k, $L.$L.getDataType()).getValue()).toList() : null",
                    param.name(), LIST, DSL, valueTable.expr(), jc.columnJavaName())
                : CodeBlock.of("$T.val(args.get($S), $L.$L.getDataType()).getValue()",
                    DSL, param.name(), valueTable.expr(), jc.columnJavaName());
            case CallSiteExtraction.NodeIdDecodeKeys nidk ->
                decodeCall(hosts.decodes(), nidk, decodesList(param), CodeBlock.of("args.get($S)", param.name()));
            case CallSiteExtraction.NestedInputField nif ->
                nestedExtraction(nif, param, valueTable, liftLocals, hosts, keys);
            case CallSiteExtraction.ElementRows rows ->
                rowsCall(hosts.elementRows(), rows, param, valueTable,
                    CodeBlock.of("args.get($S)", param.name()));
            // Request context is not in the args map: the local reads through the class's own
            // graphitronContext(env) helper, whose need the collector records so the drain and
            // the call cannot separate (the shipped-twice missing-helper bug class).
            case CallSiteExtraction.ContextArg ignored ->
                CodeBlock.of("($T) $L.getContextArgument(env, $S)",
                    localType(param), hosts.contextHelper().call(), param.name());
            case CallSiteExtraction.InputBean ib ->
                beanBuild(hosts.beans(), ib, param, CodeBlock.of("args.get($S)", param.name()));
            // Refused at classification (ServiceCatalog's condition carrier policy): the glue
            // populates no jOOQ record from an input object.
            case CallSiteExtraction.JooqRecord ignored -> throw new IllegalStateException(
                "JooqRecord is a @service parameter carrier and never a condition binding");
            case CallSiteExtraction.NodeIdDecodeRecord ignored -> throw new IllegalStateException(
                "NodeIdDecodeRecord is an input-bean field leaf only and never a condition binding");
            case CallSiteExtraction.NodeIdDecodePolymorphicRecord ignored ->
                throw new IllegalStateException("NodeIdDecodePolymorphicRecord is a @service slot"
                    + " leaf only and never a condition binding");
        };
    }

    /**
     * The call that builds a bean-typed condition parameter from its wire value, registering the
     * helper with this class's {@link InputBeanHelperRegistry}. A list-typed parameter takes the
     * {@code create<Bean>List(Object)} helper; a single one narrows the wire value with an
     * {@code instanceof Map<?, ?>} pattern rather than casting it, so the emitted local carries no
     * unchecked cast and a {@code null} or absent value yields a {@code null} bean.
     *
     * <p>List-ness is read off the declared type, as {@link #decodesList} reads it: an authored
     * condition parameter's {@code CallParam} carries no list flag, and the classifier admits a bean
     * on a list slot only as a {@code List} of it.
     */
    private static CodeBlock beanBuild(InputBeanHelperRegistry beans, CallSiteExtraction.InputBean ib,
            CallParam param, CodeBlock wireExpr) {
        if (decodesList(param)) {
            return CodeBlock.of("$L($L)", beans.plural(ib), wireExpr);
        }
        String narrowed = param.name() + "Wire";
        return CodeBlock.of("$L instanceof $T $L ? $L($L) : null",
            wireExpr, InputBeanFragments.WILDCARD_MAP, narrowed, beans.singular(ib), narrowed);
    }

    /**
     * The element-read helper call for a filter field typed as a list of input objects: the helper
     * takes the uncast wire list and the table the cells' columns belong to (the glue's own, or a
     * reach's terminal table), as the decode arm hands its descent to the decode helper. A whole
     * read only lifts values at that table's column types, so the unaliased constant serves; a
     * partial read compares against it, so it is the term's own table or hop alias.
     */
    private static CodeBlock rowsCall(ElementRowsHelperRegistry registry,
            CallSiteExtraction.ElementRows rows, CallParam param, ValueTable valueTable,
            CodeBlock wireExpr) {
        return CodeBlock.of("$L($L, $L)", registry.register(param.name(), valueTable.tableClass(), rows),
            wireExpr, valueTable.expr());
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
            ValueTable valueTable, Map<String, String> liftLocals, Hosts hosts, ProjectedKeyReads keys) {
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

        // The whole-slot install rail first: a parameter bound to the whole of a @nodeId slot has its
        // decode installed at the slot, and that contract is uniform across key arity where a
        // projection is per-column by construction, so it wins wherever both resolve. Stated as
        // precedence rather than left to the sink's arity test, which used to keep the two apart by
        // refusing every single-segment path and so also refused every inferred projection.
        if (nif.leaf() instanceof CallSiteExtraction.NodeIdDecodeKeys nidk) {
            return decodeCall(hosts.decodes(), nidk, decodesList(param),
                WireMapChain.of(root, nif.path(), null, lifted));
        }
        // A bean at the end of a dotted path: the helper takes the uncast wire value the descent
        // finds, as the top-level arm hands it args.get(name). No projected key can name an input
        // object, so the sink below has nothing to say about this leaf.
        if (nif.leaf() instanceof CallSiteExtraction.InputBean ib) {
            return beanBuild(hosts.beans(), ib, param, WireMapChain.of(root, nif.path(), null, lifted));
        }
        // A list of input objects at the end of the descent: the row helper takes the uncast list
        // the descent finds. A projected key names a column below a @nodeId, never a list level.
        if (nif.leaf() instanceof CallSiteExtraction.ElementRows rows) {
            return rowsCall(hosts.elementRows(), rows, param, valueTable,
                WireMapChain.of(root, nif.path(), null, lifted));
        }

        // Row presence decides, ahead of every remaining leaf special case: a path that opens a
        // @nodeId and names one of its key columns reads that column off a decoded record. Where the
        // encoded id sits within the path is the sink's to derive, an author who spelled the column and
        // one who let a one-column key name it having written paths of the same shape. The leaf the walk
        // resolved is an unresolvable one (the path descends past a scalar), so it arrives here as
        // Direct and none of the arms below would know the difference.
        var writtenSegments = new ArrayList<String>();
        writtenSegments.add(nif.outerArgName());
        writtenSegments.addAll(nif.path());
        var projected = keys.readFor(writtenSegments, leaf -> leaf.size() == 1
            ? CodeBlock.of("args.get($S)", nif.outerArgName())
            : WireMapChain.of(root, leaf.subList(1, leaf.size()), null, lifted));
        if (projected.isPresent()) {
            return projected.get();
        }

        if (nif.leaf() instanceof CallSiteExtraction.EnumValueOf ev) {
            // The nested twin of the top-level enum arm. Reachable since the rewrap composes the
            // parameter's own extraction onto the descent rather than defaulting the leaf: before
            // that, an enum-typed parameter at a nested input-field condition arrived as Direct and
            // the fall-through below cast a wire String to the enum type.
            var enumClass = ClassName.bestGuess(ev.enumClassName());
            return CodeBlock.of("$L instanceof $T enumWire ? $T.valueOf(enumWire) : null",
                WireMapChain.of(root, nif.path(), null, lifted), String.class, enumClass);
        }
        if (nif.leaf() instanceof CallSiteExtraction.JooqConvert jc) {
            CodeBlock chain = WireMapChain.of(root, nif.path(), null, lifted);
            if (param.list()) {
                return CodeBlock.of(
                    "$L instanceof $T<?> keys ? keys.stream().map(k -> $T.val(k, $L.$L.getDataType()).getValue()).toList() : null",
                    chain, LIST, DSL, valueTable.expr(), jc.columnJavaName());
            }
            return CodeBlock.of("$T.val($L, $L.$L.getDataType()).getValue()", DSL, chain, valueTable.expr(),
                jc.columnJavaName());
        }
        return WireMapChain.of(root, nif.path(), localType(param), lifted);
    }

}
