package no.sikt.graphitron.render;

import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.Invocation;
import no.sikt.graphitron.command.TenantStrategy;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.Ordering;
import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import javax.lang.model.element.Modifier;

/**
 * The root launcher renderer: interprets one {@link LauncherCommand} into the
 * {@code public static <Ret> rows<Field>(DSLContext dsl, DataFetchingEnvironment env)} method
 * owning the coordinate's whole query composition. Total over the command's arms plus the run's
 * {@link CarrierDsl} fact, takes no schema; the launcher cannot say anything its command does
 * not, which is what makes the thin fetcher entry point a consequence of this seam rather than a
 * discipline.
 *
 * <p>{@code dsl} is a parameter, not a local: the entry point owns connection acquisition and
 * invocation strategy, the launcher owns composition. The body composes exactly what the inline
 * fetcher chains composed: the table local, the condition (the row's glue call, or the neutral
 * condition from an absent WHERE slot), the ordering per {@link Ordering} arm, the projection
 * unit's {@code $project} select list, and the {@link ResultShape}'s terminal: {@code fetch()},
 * {@code fetchOne()}, or the connection arm's seek/limit page query wrapped in the generated
 * carrier (whose lazy resolvers read the same {@code (table, condition, page)} the query ran
 * under; on a {@link CarrierDsl#ROUTED} run the routed {@code dsl} rides the carrier too). Every
 * generated class name comes off a ref the producer minted; this renderer derives none.
 *
 * <p>Renders into the coordinate's fetchers class next to its entry point (the launcher hosts on
 * the class its {@code UnitMethodRef} owner names), so this renderer yields a {@code MethodSpec}
 * the fetcher generator folds in blindly.
 */
public final class RootLauncherRenderer {

    private RootLauncherRenderer() {}

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName SORT_FIELD = ClassName.get("org.jooq", "SortField");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final TypeName RESULT_OF_RECORD = ParameterizedTypeName.get(RESULT, RECORD);
    private static final TypeName SORT_FIELD_LIST = ParameterizedTypeName.get(
        LIST, ParameterizedTypeName.get(SORT_FIELD,
            no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class)));

    /** Renders one launcher method from its row and the run's carrier-routing fact. */
    public static MethodSpec render(LauncherCommand row, CarrierDsl carrierDsl,
            ArgPathHelperRegistry argHelpers) {
        return render(row, carrierDsl, CodeBlock.of(""), argHelpers);
    }

    /**
     * The batched overload: {@code batchedDslDeclaration} is the coordinate's single-tenant
     * {@code dsl} declaration fragment, per-family argument assembly handed in by the shell
     * (the tenancy binding's declaration form is classification-side emission the command must
     * not hold). Ignored by every non-batched row.
     */
    public static MethodSpec render(LauncherCommand row, CarrierDsl carrierDsl,
            CodeBlock batchedDslDeclaration, ArgPathHelperRegistry argHelpers) {
        return render(row, carrierDsl, batchedDslDeclaration, CodeBlock.of(""), argHelpers);
    }

    /**
     * The service overload: {@code serviceCall} is the composed
     * {@code <target>.<method>(<args>)} expression, the second and last shell argument-assembly
     * fragment beside the {@code dsl} declaration (the call's argument extraction rides the
     * emission context's per-class helper naming, which the command must not hold). Ignored by
     * every non-service row.
     */
    public static MethodSpec render(LauncherCommand row, CarrierDsl carrierDsl,
            CodeBlock batchedDslDeclaration, CodeBlock serviceCall,
            ArgPathHelperRegistry argHelpers) {
        var builder = MethodSpec.methodBuilder(row.unit().methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(valueTypeOf(row));
        // The parameter list is a derived view over (invocation, tenancy), total over the
        // delivery axis so a new arm is a compile error here rather than a silent default: a
        // direct single-tenant launcher takes the one resolved DSLContext its entry point
        // acquired; a fanned launcher takes none, its acquisition being plural and internal to
        // the scatter carrier; a batched launcher takes its loader keys; a reentry companion
        // takes the write's captured RETURNING keys, the dsl declared in the body by the
        // shell's fragment.
        switch (row.invocation()) {
            case Invocation.Batched batched ->
                builder.addParameter(BatchedRowsFragments.keysType(batched), "keys");
            case Invocation.ReturningKeyed ignored ->
                builder.addParameter(ReentryRowsFragments.keysType(row), "keys");
            case Invocation.Direct ignored -> {
                if (row.tenancy() instanceof TenantStrategy.Single) {
                    builder.addParameter(DSL_CONTEXT, "dsl");
                }
            }
        }
        builder.addParameter(ENV, "env");

        switch (row.source()) {
            case LaunchSource.AnchorTable anchor -> {
                String tableLocal = TableLocal.name(anchor.table());
                builder.addCode(TableLocal.declare(anchor.table()));
                builder.addCode(conditionStatement(row, tableLocal));
                switch (row.tenancy()) {
                    case TenantStrategy.Single ignored -> {
                        switch (row.result()) {
                            case ResultShape.SingleRecord ignored2 -> builder.addCode(
                                directReturn(selectChain(anchor, tableLocal, false, false)));
                            case ResultShape.RecordList list -> {
                                boolean ordered = list.ordering() != null;
                                if (ordered) {
                                    builder.addCode(orderByStatement(list.ordering(), tableLocal));
                                }
                                builder.addCode(directReturn(selectChain(anchor, tableLocal, true, ordered)));
                            }
                            case ResultShape.Connection connection ->
                                builder.addCode(connectionBody(anchor, connection, tableLocal, carrierDsl));
                            case ResultShape.LoaderDelegated ignored2 -> throw new IllegalStateException(
                                "the LoaderDelegated result belongs to the service arms; the"
                                + " command constructor rejects the pair before rendering");
                        }
                    }
                    case TenantStrategy.Fanned fanned ->
                        builder.addCode(fannedBody(anchor, row, fanned, tableLocal));
                }
            }
            case LaunchSource.RoutineChain chain -> builder.addCode(routineBody(row, chain, argHelpers));
            case LaunchSource.Correlated chain ->
                builder.addCode(BatchedRowsFragments.body(row, chain, batchedDslDeclaration, carrierDsl, argHelpers));
            case LaunchSource.PivotAggregate pivot ->
                builder.addCode(BatchedRowsFragments.pivotBody(row, pivot, batchedDslDeclaration, argHelpers));
            case LaunchSource.ServiceCall sc ->
                builder.addCode(ServiceRowsFragments.delegateBody(sc, batchedDslDeclaration, serviceCall));
            case LaunchSource.ServiceTableLift lift ->
                builder.addCode(ServiceRowsFragments.liftBody(row, lift, batchedDslDeclaration, serviceCall));
            case LaunchSource.KeyedLookup lookup -> {
                String tableLocal = TableLocal.name(lookup.table());
                builder.addCode(TableLocal.declare(lookup.table()));
                builder.addCode(conditionStatement(row, tableLocal));
                builder.addCode(lookupBody(row, lookup, tableLocal));
            }
            case LaunchSource.ProjectedReentry projected -> builder.addCode(
                ReentryRowsFragments.projectedBody(row, projected, batchedDslDeclaration));
            case LaunchSource.DiscriminatedReentry discriminated -> builder.addCode(
                ReentryRowsFragments.discriminatedBody(row, discriminated, batchedDslDeclaration));
            case LaunchSource.DiscriminatedTable disc -> {
                String tableLocal = TableLocal.name(disc.table());
                builder.addCode(TableLocal.declare(disc.table()));
                builder.addCode(conditionStatement(row, tableLocal));
                // The whole discriminated assembly (routing projection, participant select
                // list, gated LEFT JOIN arms) is the shared fragment; only the terminal is
                // shape-forked here, over the same fetch tail the anchor arm chains.
                builder.addCode(DiscriminatedTableFragments.assembly(disc,
                    java.util.List.of(), tableLocal));
                switch (row.result()) {
                    case ResultShape.SingleRecord ignored ->
                        builder.addCode(directReturn(stepChain(false, false)));
                    case ResultShape.RecordList list -> {
                        boolean ordered = list.ordering() != null;
                        if (ordered) {
                            builder.addCode(orderByStatement(list.ordering(), tableLocal));
                        }
                        builder.addCode(directReturn(stepChain(true, ordered)));
                    }
                    case ResultShape.Connection ignored -> throw new IllegalStateException(
                        "a discriminated-interface launcher never paginates; the command"
                        + " constructor rejects the pair before rendering");
                    case ResultShape.LoaderDelegated ignored -> throw new IllegalStateException(
                        "the LoaderDelegated result belongs to the service arms; the"
                        + " command constructor rejects the pair before rendering");
                }
            }
        }
        return builder.build();
    }

    /**
     * The launcher's rendered payload, a derived view over {@code (invocation, tenancy,
     * result)}: the fanned strategy returns the scatter's marker-bearing transport ({@code List<Object>},
     * entailed by the strategy, collapsed by the entry point), everything else the shape's own
     * type (the connection arm's is its carrier ref). Read by this renderer and the entry-point
     * emitter, so the two ends cannot disagree.
     */
    public static TypeName valueTypeOf(LauncherCommand row) {
        if (row.source() instanceof LaunchSource.ServiceCall
                || row.source() instanceof LaunchSource.ServiceTableLift) {
            return ServiceRowsFragments.valueTypeOf(row);
        }
        // Total over the delivery axis, so a new arm is a compile error at this view too.
        return switch (row.invocation()) {
            case Invocation.Batched ignored -> BatchedRowsFragments.valueTypeOf(row);
            case Invocation.ReturningKeyed ignored -> ReentryRowsFragments.valueTypeOf(row);
            case Invocation.Direct ignored -> {
                if (row.tenancy() instanceof TenantStrategy.Fanned) {
                    yield ParameterizedTypeName.get(LIST, ClassName.get(Object.class));
                }
                yield switch (row.result()) {
                    // The keyed lookup is the one list arm that does not hand back its fetch:
                    // it scatters into one slot per input key, and a slot for an unmatched key
                    // holds null, which a jOOQ Result does not carry.
                    case ResultShape.RecordList ignored2 ->
                        row.source() instanceof LaunchSource.KeyedLookup
                            ? ParameterizedTypeName.get(LIST, RECORD)
                            : RESULT_OF_RECORD;
                    case ResultShape.SingleRecord ignored2 -> RECORD;
                    case ResultShape.Connection connection -> className(connection.carrier());
                    case ResultShape.LoaderDelegated ignored2 -> throw new IllegalStateException(
                        "the LoaderDelegated result belongs to the service arms, whose value type"
                        + " derives in ServiceRowsFragments; the command constructor rejects the pair");
                };
            }
        };
    }

    /**
     * The one composition fragment both invocation arms share: the select chain over a
     * caller-supplied select expression (the inline {@code $project} call for the direct arm,
     * the hoisted {@code selectFields} local for the fanned lambda), with {@code dsl} bound by
     * the signature (direct) or by the strategy's per-tenant lambda (fanned). One derivation,
     * two binders, which is "one composition, two invocations" as structure.
     */
    private static CodeBlock selectChain(CodeBlock selectExpr, String tableLocal, boolean isList, boolean ordered) {
        var chain = CodeBlock.builder()
            .add("dsl\n")
            .indent()
            .add(".select($L)\n", selectExpr)
            .add(".from($L)\n", tableLocal)
            .add(conditionedFetchTail(isList, ordered))
            .unindent();
        return chain.build();
    }

    /**
     * The discriminated arm's chain: the source assembly already yielded the joined {@code step}
     * local, so the FROM prefix is that local and the tail is the same conditioned fetch the
     * anchor arm chains. The routine arm stays outside this pair deliberately: its WHERE operand
     * is the hop-filter fold, not the {@code condition} local, so folding it in would trade the
     * shared invariant for a template.
     */
    private static CodeBlock stepChain(boolean isList, boolean ordered) {
        return CodeBlock.builder()
            .add("step\n")
            .indent()
            .add(conditionedFetchTail(isList, ordered))
            .unindent()
            .build();
    }

    /**
     * The condition-bearing sources' one terminal fragment: {@code .where(condition)}, the
     * optional sort-view ordering, and the cardinality's fetch. The FROM prefix is the source
     * arm's to supply ({@link #selectChain} or {@link #stepChain}).
     */
    private static CodeBlock conditionedFetchTail(boolean isList, boolean ordered) {
        var tail = CodeBlock.builder().add(".where(condition)\n");
        if (ordered) {
            tail.add(".orderBy(orderBy)\n");
        }
        tail.add(isList ? ".fetch()" : ".fetchOne()");
        return tail.build();
    }

    private static CodeBlock selectChain(LaunchSource.AnchorTable anchor, String tableLocal,
            boolean isList, boolean ordered) {
        return selectChain(ProjectionCall.fromEnvSelection(className(anchor.projection()), tableLocal),
            tableLocal, isList, ordered);
    }

    private static CodeBlock directReturn(CodeBlock chain) {
        return CodeBlock.builder().add("return $L;\n", chain).build();
    }

    /**
     * The fanned arm: every env-derived value is hoisted onto the dispatch thread (the ordering
     * views and the projected select list become locals), and the per-tenant lambda closes over
     * those locals plus its own {@code dsl}, so scatter workers read no shared graphql-java
     * state by construction; the entry point collapses the returned outcome list.
     */
    private static CodeBlock fannedBody(LaunchSource.AnchorTable anchor, LauncherCommand row,
            TenantStrategy.Fanned fanned, String tableLocal) {
        var list = (ResultShape.RecordList) row.result();
        var code = CodeBlock.builder();
        boolean ordered = list.ordering() != null;
        if (ordered) {
            code.add(orderByStatement(list.ordering(), tableLocal));
        }
        var listOfField = ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"),
                no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class)));
        code.addStatement("$T selectFields = $L", listOfField,
            ProjectionCall.fromEnvSelection(className(anchor.projection()), tableLocal));
        code.add("return $T.fanOutRows(env, dsl -> $L);\n", className(fanned.carrier()),
            selectChain(CodeBlock.of("selectFields"), tableLocal, true, ordered));
        return code.build();
    }

    /**
     * The connection arm: the two-view ordering block, the four fixed pagination argument reads
     * (names fixed by the slot; the classifier rejects custom names), the page request, the
     * seek/limit page query, and the carrier construction over the same
     * {@code (result, page, table, condition)} the query ran under. A faceted plan additionally
     * binds the base fragment, the per-facet condition map, and the decode specs through the
     * carrier's facet-carrying constructor.
     */
    private static CodeBlock connectionBody(LaunchSource.AnchorTable anchor, ResultShape.Connection connection,
            String tableLocal, CarrierDsl carrierDsl) {
        var code = CodeBlock.builder();
        code.add(OrderingBlock.declareBothViews(connection.ordering(), tableLocal));
        code.addStatement("Integer first = env.getArgument($S)", "first");
        code.addStatement("Integer last = env.getArgument($S)", "last");
        code.addStatement("String after = env.getArgument($S)", "after");
        code.addStatement("String before = env.getArgument($S)", "before");
        var helperClass = className(connection.helper());
        code.addStatement(
            "$T page = $T.pageRequest(first, last, after, before, $L, orderBy, extraFields, $L)",
            helperClass.nestedClass("PageRequest"), helperClass, connection.defaultPageSize(),
            ProjectionCall.fromEnvSelection(className(anchor.projection()), tableLocal));
        code.add("$T result = dsl\n", RESULT_OF_RECORD)
            .indent()
            .add(".select(page.selectFields())\n")
            .add(".from($L)\n", tableLocal)
            .add(".where(condition)\n")
            .add(".orderBy(page.effectiveOrderBy())\n")
            .add(".seek(page.seekFields())\n")
            .add(".limit(page.limit())\n")
            .add(".fetch();\n")
            .unindent();
        String dslTail = carrierDsl == CarrierDsl.ROUTED ? ", dsl" : "";
        if (connection.facets() == null) {
            code.addStatement("return new $T(result, page, $L, condition$L)",
                className(connection.carrier()), tableLocal, dslTail);
        } else {
            code.add(facetBindings(connection, tableLocal));
            code.addStatement(
                "return new $T(result, page, $L, condition, facetBase, facetConditions, facetSpecs$L)",
                className(connection.carrier()), tableLocal, dslTail);
        }
        return code.build();
    }

    /** The faceted carrier's bindings: the base fragment, the per-facet map, the decode specs. */
    private static CodeBlock facetBindings(ResultShape.Connection connection, String tableLocal) {
        var plan = connection.facets();
        var code = CodeBlock.builder();
        code.addStatement("$T facetBase = $L", CONDITION, glueExpression(plan.base(), tableLocal));
        code.addStatement("$T<String, $T> facetConditions = new $T<>()",
            ClassName.get("java.util", "Map"), CONDITION, ClassName.get("java.util", "LinkedHashMap"));
        for (var facet : plan.facets()) {
            code.addStatement("facetConditions.put($S, $L)",
                facet.spec().inputFieldName(), glueExpression(facet.condition(), tableLocal));
        }
        var facetSpecRuntime = className(connection.carrier()).nestedClass("FacetSpec");
        var specsArgs = CodeBlock.builder();
        boolean firstSpec = true;
        for (var facet : plan.facets()) {
            if (!firstSpec) {
                specsArgs.add(",\n    ");
            }
            firstSpec = false;
            specsArgs.add("new $T($S, $S, $L)", facetSpecRuntime,
                facet.spec().inputFieldName(), facet.spec().columnName(), facet.spec().valueNullable());
        }
        code.addStatement("$T<$T> facetSpecs = $T.of($L)",
            LIST, facetSpecRuntime, LIST, specsArgs.build());
        return code.build();
    }

    /**
     * The keyed-lookup arm: the input-rows helper builds the typed {@code Row[]} off the field's
     * arguments (a same-class private the launcher calls unqualified, its ref minted beside the
     * launcher's own), the empty-input short-circuit returns the empty result with no statement
     * issued, and the VALUES derived table joins the anchor with {@code USING} over the mapping's
     * key columns. The WHERE stays the condition local, the same glue-or-neutral fold every
     * condition-bearing arm chains.
     *
     * <p>The list arm delivers one output slot per input key, null where the key matched no row,
     * which is the lookup's whole point: the caller reads position {@code i} as the answer for
     * key {@code i}. A join alone cannot do that, since an unmatched key contributes no row and
     * silently shortens the result, so the derived table's {@code idx} rides the select list as
     * {@code __idx__} and {@code scatterLookupByIdx} places each row at its key's position. That
     * scatter is also what carries the input ordering, so the arm needs no {@code ORDER BY}.
     *
     * <p>The single arm needs none of it: one key has one slot, and an unmatched key is the
     * {@code null} that {@code fetchOne} already returns.
     */
    private static CodeBlock lookupBody(LauncherCommand row, LaunchSource.KeyedLookup lookup,
            String tableLocal) {
        String fieldName = row.coordinate().getFieldName();
        String alias = LookupRows.inputTableAlias(fieldName);
        var mapping = lookup.mapping();
        boolean isList = row.result() instanceof ResultShape.RecordList;
        var code = CodeBlock.builder();
        code.addStatement("$T rows = $L(env, $L)",
            LookupRows.rowArrayType(mapping, fieldName), lookup.inputRows().methodName(), tableLocal);
        code.add(isList
            ? CodeBlock.of("if (rows.length == 0) return $T.of();\n", LIST)
            : CodeBlock.of("if (rows.length == 0) return null;\n"));
        code.addStatement("$T input = $T.values(rows).as($L)",
            LookupRows.inputTableType(mapping, fieldName), DSL,
            LookupRows.aliasArgs(mapping, alias, fieldName));
        var projection = ProjectionCall.fromEnvSelection(className(lookup.projection()), tableLocal);
        if (isList) {
            code.addStatement("$T<$T<?>> selectFields = new $T<>($L)", LIST, FIELD, ARRAY_LIST, projection);
            // Through ReservedAliases, not a literal: this is the writer half of the
            // writer-alias-equals-reader-alias invariant that SplitRowsMethodEmitter's scatter
            // helper reads back.
            code.addStatement("selectFields.add(input.field($S).as($S))", "idx", ReservedAliases.IDX);
        }
        code.add(isList ? CodeBlock.of("$T flat = dsl\n", RESULT_OF_RECORD) : CodeBlock.of("return dsl\n"))
            .indent()
            .add(".select($L)\n", isList ? CodeBlock.of("selectFields") : projection)
            .add(".from($L)\n", tableLocal)
            .add(".join(input).using($L)\n", LookupRows.usingArgs(mapping, tableLocal, fieldName))
            .add(".where(condition)\n")
            .add(isList ? ".fetch();\n" : ".fetchOne();\n")
            .unindent();
        if (isList) {
            code.addStatement("return scatterLookupByIdx(flat, rows.length)");
        }
        return code.build();
    }

    /**
     * The routine-chain arm: the FROM source is the bound table-valued function (IN parameters
     * read off the field's arguments through the shared routine-call emitter), hops join forward
     * out of the routine result through the shared bridging fragment, hop filters AND into one
     * WHERE, and the projection targets the terminus alias. No condition local: the leaf carries
     * no filter surface (the coordinate can have no condition row), and the chain's WHERE is the
     * hop filters alone, exactly as the inline builder composed it.
     */
    private static CodeBlock routineBody(LauncherCommand row, LaunchSource.RoutineChain chain,
            ArgPathHelperRegistry argHelpers) {
        var code = CodeBlock.builder();
        var startTable = chain.start().resultTable();
        String startLocal = chain.hops().isEmpty() ? TableLocal.name(startTable) : "source";
        code.addStatement("$T $L = $L", startTable.tableClass(), startLocal,
            RoutineCallEmitter.emitCall(chain.start(), new PreviousNodeRef.None(),
                new ArgumentValueSource.Env(), argHelpers));
        for (var hop : chain.hops()) {
            // The chain constructor pins every hop target to the catalog, so the alias wraps the
            // bare Tables.<X> singleton, matching every other alias-declaration site.
            code.addStatement("$T $L = $T.$L.as($S)",
                hop.targetTable().tableClass(), hop.alias(),
                hop.targetTable().constantsClass(), hop.targetTable().javaFieldName(), hop.alias());
        }
        String terminal = chain.hops().isEmpty() ? startLocal : chain.hops().getLast().alias();
        boolean isList = row.result() instanceof ResultShape.RecordList;

        var sel = CodeBlock.builder()
            .add("return dsl\n")
            .indent()
            .add(".select($L)\n", ProjectionCall.fromEnvSelection(className(chain.projection()), terminal))
            .add(".from($L)\n", startLocal);
        var filters = new java.util.ArrayList<CodeBlock>();
        for (int i = 0; i < chain.hops().size(); i++) {
            var hop = chain.hops().get(i);
            String prev = i == 0 ? startLocal : chain.hops().get(i - 1).alias();
            sel.add("$L\n", PathFragments.emitForwardBridging(hop, prev, hop.alias()));
            if (hop.filter() != null) {
                filters.add(PathFragments.emitTwoArgMethodCall(hop.filter(), prev, hop.alias()));
            }
        }
        if (!filters.isEmpty()) {
            sel.add(".where($L)\n", filters.stream()
                .reduce((a, b) -> CodeBlock.of("$L.and($L)", a, b)).orElseThrow());
        }
        sel.add(isList ? ".fetch();\n" : ".fetchOne();\n").unindent();
        code.add(sel.build());
        return code.build();
    }

    /**
     * {@code Condition condition = <Parent>Conditions.<field>Condition(<table>, env.getArguments()[, env]);}
     * from the row's glue call, or the neutral condition when the WHERE slot is absent (the
     * coordinate has no condition row; absence is data, never an inline fold).
     */
    private static CodeBlock conditionStatement(LauncherCommand row, String tableLocal) {
        if (row.where() == null) {
            return CodeBlock.builder().addStatement("$T condition = $T.noCondition()", CONDITION, DSL).build();
        }
        return CodeBlock.builder()
            .addStatement("$T condition = $L", CONDITION, glueExpression(row.where(), tableLocal))
            .build();
    }

    /** {@code <Owner>.<method>(<table>, env.getArguments()[, env])} for any glue reference. */
    static CodeBlock glueExpression(no.sikt.graphitron.command.GlueCall glue, String tableLocal) {
        var call = CodeBlock.builder().add("$T.$L($L, env.getArguments()",
            className(glue.method().owner()), glue.method().methodName(), tableLocal);
        if (glue.takesEnv()) {
            call.add(", env");
        }
        call.add(")");
        return call.build();
    }

    /** The single/list shapes' sort-view-only ordering statement. */
    private static CodeBlock orderByStatement(Ordering ordering, String tableLocal) {
        return switch (ordering) {
            case Ordering.Columns columns -> CodeBlock.builder()
                .addStatement("$T orderBy = $T.of($L)", SORT_FIELD_LIST, LIST,
                    OrderByFragments.fixedSortParts(columns.spec(), tableLocal))
                .build();
            // The helper is a same-class private static (launcher and helper share the fetchers
            // class by the naming vocabulary), so the call is unqualified, matching the entry
            // points that call it today.
            case Ordering.Helper helper -> CodeBlock.builder()
                .addStatement("$T orderBy = $L(env, $L).sortFields()",
                    SORT_FIELD_LIST, helper.method().methodName(), tableLocal)
                .build();
        };
    }

    private static ClassName className(no.sikt.graphitron.command.UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
