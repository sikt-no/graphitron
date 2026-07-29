package no.sikt.graphitron.render;

import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.TenantStrategy;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

/**
 * The batched child launcher's composition fragments: the parent-input VALUES derived table
 * built from the delivery arm's key facts, the correlation's step-0 attach, the forward hop
 * chain to the terminal, the WHERE fold (per-hop filters plus the coordinate's condition glue),
 * and the per-cardinality scatter tails. Twin of the retiring split rows-method emission, in
 * the {@code DiscriminatedTableFragments} shape: command arms in, javapoet fragments out,
 * preconditions as named in-scope locals ({@code keys}, {@code env}, and for the single-tenant
 * form a {@code dsl} declared by the caller-supplied declaration fragment) rather than a
 * context object. The reserved {@code __idx__} scatter column is arm-entailed: constant for
 * this delivery, so no extras slot carries it.
 */
final class BatchedRowsFragments {

    private BatchedRowsFragments() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");
    private static final ClassName LIST_CN = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");

    /** SELECT-projection alias for the parent-input index; the scatter key. */
    static final String IDX_COLUMN = "__idx__";

    /**
     * The whole rows-method body for one batched child row: skeleton framing (empty-keys gate,
     * the caller-supplied single-tenant {@code dsl} declaration), the prelude, the topology,
     * the WHERE fold, and the cardinality's scatter tail. {@code dslDeclaration} is per-family
     * argument assembly handed in by the shell (the tenancy binding's declaration form is
     * classification-side emission); it is ignored under fanned tenancy, whose {@code dsl} is
     * the scatter lambda's parameter.
     */
    static CodeBlock body(LauncherCommand row, LaunchSource.CorrelatedChain chain,
            CodeBlock dslDeclaration) {
        var batched = (no.sikt.graphitron.command.Invocation.Batched) row.invocation();
        String fieldName = row.coordinate().getFieldName();
        var body = CodeBlock.builder();

        // Skeleton framing: the empty-keys gate, then the dsl declaration (single-tenant only;
        // the fanned form's dsl is the lambda parameter).
        body.beginControlFlow("if (keys.isEmpty())");
        body.addStatement("return $T.of()", LIST_CN);
        body.endControlFlow();
        if (row.tenancy() instanceof TenantStrategy.Single) {
            body.add(dslDeclaration);
        }

        var prelude = prelude(body, fieldName, batched.sourceKey(), chain);

        // Projection: $project(env.getSelectionSet(), terminalAlias, env) + idx.as("__idx__").
        // Typed idx access via parentInput.field(0, Integer.class); see the retired emitter's
        // note on Table.fieldsRow's untyped return.
        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST_CN, wildField);
        body.addStatement("$T selectFields = new $T<>($L)",
            listOfField, ARRAY_LIST,
            ProjectionCall.fromEnvSelection(className(chain.projection()), prelude.terminalAlias()));
        body.addStatement("selectFields.add(parentInput.field(0, $T.class).as($S))",
            Integer.class, IDX_COLUMN);

        var sel = CodeBlock.builder();
        sel.add("$T<$T> flat = dsl\n", RESULT, RECORD);
        sel.indent();
        sel.add(".select(selectFields)\n");
        fromBridgeAndParentJoin(sel, chain.joinPath(), prelude.aliases(), prelude.firstAlias(),
            chain.correlation(), prelude.joinOnAlias(), prelude.joinOnCols(), prelude.joinOnParentCols());
        sel.add(".where($L)\n", whereCondition(row, chain, prelude));
        sel.add(".fetch();\n");
        sel.unindent();

        boolean single = row.result() instanceof no.sikt.graphitron.command.ResultShape.SingleRecord;
        if (row.tenancy() instanceof TenantStrategy.Fanned fanned) {
            // The fanned batched form: the same batch statement, one execution per domain
            // tenant through the scatter helper; per-key groups merge across tenants with
            // per-element tenant stamping, markers riding to the fetcher's collapse.
            var tenantConnections = ClassName.get(fanned.carrier().packageName(), fanned.carrier().simpleName());
            body.add("return $T.fanOutBatchRows(env, keys.size(), dsl -> {\n", tenantConnections);
            body.indent();
            body.add(sel.build());
            body.add("return scatterByIdx(flat, keys.size());\n");
            body.unindent();
            body.add("});\n");
            return body.build();
        }
        body.add(sel.build());
        body.addStatement(single
            ? "return scatterSingleByIdx(flat, keys.size())"
            : "return scatterByIdx(flat, keys.size())");
        return body.build();
    }

    /**
     * The rendered payload for a batched row, read by the launcher renderer and the entry-point
     * emitter: {@code List<Record>} for the single-per-key shape, {@code List<List<Record>>}
     * for the list shape, and the scatter's marker-bearing {@code List<List<Object>>} transport
     * under fanned tenancy.
     */
    static TypeName valueTypeOf(LauncherCommand row) {
        if (row.tenancy() instanceof TenantStrategy.Fanned) {
            return ParameterizedTypeName.get(LIST_CN,
                ParameterizedTypeName.get(LIST_CN, ClassName.get(Object.class)));
        }
        TypeName listOfRecord = ParameterizedTypeName.get(LIST_CN, RECORD);
        return row.result() instanceof no.sikt.graphitron.command.ResultShape.SingleRecord
            ? listOfRecord
            : ParameterizedTypeName.get(LIST_CN, listOfRecord);
    }

    /** The keys parameter's type: {@code List<K>} over the source key's element type. */
    static TypeName keysType(no.sikt.graphitron.command.Invocation.Batched batched) {
        return ParameterizedTypeName.get(LIST_CN, batched.sourceKey().keyElementType());
    }

    private record PreludeBindings(
        List<String> aliases,
        String terminalAlias,
        String firstAlias,
        String joinOnAlias,
        List<ColumnRef> joinOnCols,
        List<ColumnRef> joinOnParentCols
    ) {}

    /**
     * The five-act prelude: typed {@code parentRows[]} VALUES with its scoped
     * {@code @SuppressWarnings} cast, the {@code parentInput} derived-table aliasing, the
     * FK-chain alias declarations, and the correlation arm's one-time resolution of the
     * parent-input JOIN's alias and column pairs. The empty-keys gate and {@code dsl}
     * declaration precede this in {@link #body}.
     */
    private static PreludeBindings prelude(CodeBlock.Builder body, String fieldName,
            SourceKey sourceKey, LaunchSource.CorrelatedChain chain) {
        List<JoinStep> joinPath = chain.joinPath();
        ParentCorrelation correlation = chain.correlation();
        List<ColumnRef> pkCols = sourceKey.columns();
        TypeName keyElement = sourceKey.keyElementType();

        int parentRowArity = pkCols.size() + 1;
        if (parentRowArity > 22) {
            throw new IllegalStateException(
                "Parent PK arity " + pkCols.size() + " + idx exceeds jOOQ's typed Row/Record arity limit (22)");
        }
        TypeName[] parentRowTypeArgs = new TypeName[parentRowArity];
        parentRowTypeArgs[0] = ClassName.get(Integer.class);
        for (int i = 0; i < pkCols.size(); i++) {
            parentRowTypeArgs[i + 1] = pkCols.get(i).columnType();
        }
        // ValuesJoinRowBuilder's schemes take the slot count and add the idx cell themselves.
        TypeName parentRowType = ParameterizedTypeName.get(ValuesJoinRowBuilder.rowClass(pkCols.size()), parentRowTypeArgs);
        TypeName parentRecordType = ParameterizedTypeName.get(ValuesJoinRowBuilder.recordClass(pkCols.size()), parentRowTypeArgs);
        TypeName parentInputTableType = ParameterizedTypeName.get(TABLE, parentRecordType);

        // The command constructor's contract mirrors the classifier's: a correlated chain is
        // non-empty except under the pre-keyed lifted shape.
        if (joinPath.isEmpty() && !(correlation instanceof ParentCorrelation.OnLiftedSlots)) {
            throw new IllegalStateException(
                "a batched child row for field '" + fieldName + "' carries an empty join path"
                + " outside the pre-keyed lifted shape; standalone references render inline,"
                + " never as batched rows methods");
        }
        List<String> aliases = correlation instanceof ParentCorrelation.OnLiftedSlots lifted
            ? List.of(PathFragments.liftedAlias(lifted.targetTable()))
            : PathFragments.generateAliases(joinPath);
        String terminalAlias = aliases.get(aliases.size() - 1);
        String firstAlias = aliases.get(0);
        String joinOnAlias;
        List<ColumnRef> joinOnCols;
        List<ColumnRef> joinOnParentCols;
        switch (correlation) {
            case ParentCorrelation.OnFkSlots fk -> {
                var firstSlots = fk.slots();
                joinOnAlias = firstAlias;
                joinOnCols = firstSlots.targetSideColumns();
                joinOnParentCols = firstSlots.sourceSideColumns();
            }
            case ParentCorrelation.OnLiftedSlots lifted -> {
                joinOnAlias = firstAlias;
                joinOnCols = lifted.columns();
                joinOnParentCols = lifted.columns();
            }
            case ParentCorrelation.OnParentJoin pj -> {
                joinOnAlias = "parentAlias";
                joinOnCols = pj.parentKeyColumns();
                joinOnParentCols = pj.parentKeyColumns();
            }
            case ParentCorrelation.OnLateralArgs ignored -> {
                joinOnAlias = firstAlias;
                joinOnCols = List.of();
                joinOnParentCols = List.of();
            }
        }

        // Parent-input VALUES rows, fully typed: one Row<N+1><Integer, pkType1, ...> per key[i].
        // Generic array creation is the one unavoidable unchecked cast, scoped to this line.
        body.add("@$T({$S, $S})\n", ClassName.get("java.lang", "SuppressWarnings"), "unchecked", "rawtypes");
        body.addStatement("$T[] parentRows = ($T[]) new $T[keys.size()]",
            parentRowType, parentRowType, ValuesJoinRowBuilder.rowClass(pkCols.size()));
        body.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        body.addStatement("$T k = keys.get(i)", keyElement);
        body.addStatement("parentRows[i] = $T.row($L)", DSL,
            parentKeyCells(sourceKey, pkCols, correlation.parentKeyOwnerTable()));
        body.endControlFlow();

        var parentInputAlias = CodeBlock.builder();
        parentInputAlias.add("$S, $S", "parentInput", "idx");
        for (var col : pkCols) {
            parentInputAlias.add(", $S", col.sqlName());
        }
        body.addStatement("$T parentInput = $T.values(parentRows).as($L)",
            parentInputTableType, DSL, parentInputAlias.build());

        // Hop aliases, one declaration per hop; the lifted shape has no hops and declares its
        // single synthesized target alias directly.
        if (correlation instanceof ParentCorrelation.OnLiftedSlots lifted) {
            TableRef liftedTarget = lifted.targetTable();
            body.addStatement("$T $L = $T.$L.as($S)",
                liftedTarget.tableClass(), firstAlias,
                liftedTarget.constantsClass(), liftedTarget.javaFieldName(),
                fieldName + "_" + firstAlias);
        }
        for (int i = 0; i < joinPath.size(); i++) {
            JoinStep.HasTargetTable step = (JoinStep.HasTargetTable) joinPath.get(i);
            ClassName jooqTableClass = step.targetTable().tableClass();
            PreviousNodeRef previousNode = i > 0
                ? new PreviousNodeRef.TypedAlias(aliases.get(i - 1))
                : correlation instanceof ParentCorrelation.OnLateralArgs
                    ? new PreviousNodeRef.ParentInputField("parentInput", correlation.parentKeyOwnerTable())
                    : new PreviousNodeRef.TypedAlias("parentAlias");
            body.addStatement("$T $L = $L.as($S)",
                jooqTableClass, aliases.get(i),
                PathFragments.emitTableExpression(joinPath.get(i), previousNode,
                    new ArgumentValueSource.Env()),
                fieldName + "_" + aliases.get(i));
        }

        if (correlation instanceof ParentCorrelation.OnParentJoin pj) {
            TableRef parentTable = pj.parentTable();
            body.addStatement("$T parentAlias = $T.$L.as($S)",
                parentTable.tableClass(), parentTable.constantsClass(), parentTable.javaFieldName(),
                fieldName + "_parent");
        }

        return new PreludeBindings(aliases, terminalAlias, firstAlias, joinOnAlias, joinOnCols, joinOnParentCols);
    }

    /**
     * One parent-input VALUES row's cell list, through the single VALUES-cell authority so
     * converter-backed and domain-typed keys bind at the column's registered {@code DataType}.
     * The scalar extraction forks on {@link SourceKey.Wrap}: {@code Record} keys read
     * {@code k.valueN()}; {@code Row} keys recover the value from the bind {@code Param} via
     * the per-class {@code parentKeyCellValue} helper; {@code TableRecord} keys never reach the
     * parent-input seam.
     */
    private static CodeBlock parentKeyCells(SourceKey sourceKey, List<ColumnRef> pkCols, TableRef ownerTable) {
        CodeBlock ownerExpr = CodeBlock.of("$T.$L", ownerTable.constantsClass(), ownerTable.javaFieldName());
        java.util.function.BiFunction<ColumnRef, Integer, CodeBlock> valueExpr = switch (sourceKey.wrap()) {
            case SourceKey.Wrap.Record ignored -> (col, i) -> CodeBlock.of("k.value$L()", i + 1);
            case SourceKey.Wrap.Row ignored -> (col, i) -> CodeBlock.of("parentKeyCellValue(k.field$L())", i + 1);
            case SourceKey.Wrap.TableRecord tr -> throw new IllegalStateException(
                "SourceKey.Wrap.TableRecord (" + tr.className() + ") cannot reach the parent-input "
                + "VALUES seam; TableRecord-keyed variants do not emit a parent-input rows method.");
        };
        return ValuesJoinRowBuilder.cellsCode(
            pkCols, java.util.function.Function.identity(),
            CodeBlock.of("$T.inline(i)", DSL), ownerExpr, valueExpr);
    }

    /**
     * The flat join topology: {@code .from(parentInput)}, the step-0 attach per correlation
     * arm (including the optional parent JOIN), then the forward bridging hops out to the
     * terminal. Stops before WHERE. The parent-input field is resolved by sqlName plus the
     * owner column's {@code DataType}, never positionally, keeping converter-backed columns'
     * type metadata faithful.
     */
    private static void fromBridgeAndParentJoin(
            CodeBlock.Builder sel,
            List<JoinStep> path,
            List<String> aliases,
            String firstAlias,
            ParentCorrelation correlation,
            String joinOnAlias,
            List<ColumnRef> joinOnCols,
            List<ColumnRef> joinOnParentCols) {
        TableRef ownerTable = correlation.parentKeyOwnerTable();
        var onCond = CodeBlock.builder();
        for (int i = 0; i < joinOnCols.size(); i++) {
            if (i > 0) onCond.add(".and(");
            onCond.add("$L.$L.eq($L)",
                joinOnAlias,
                joinOnCols.get(i).javaName(),
                parentInputFieldLookup(joinOnParentCols.get(i), ownerTable));
            if (i > 0) onCond.add(")");
        }
        sel.add(".from(parentInput)\n");
        switch (correlation) {
            case ParentCorrelation.OnFkSlots ignored ->
                sel.add(".join($L).on($L)\n", firstAlias, onCond.build());
            case ParentCorrelation.OnLiftedSlots ignored ->
                sel.add(".join($L).on($L)\n", firstAlias, onCond.build());
            case ParentCorrelation.OnParentJoin pj -> {
                sel.add(".join(parentAlias).on($L)\n", onCond.build());
                switch (pj.firstHop().on()) {
                    case On.ColumnPairs cp -> sel.add("$L\n",
                        JoinFragments.emitForwardJoin(cp, "parentAlias", firstAlias));
                    case On.Predicate pred -> sel.add(".join($L).on($L)\n", firstAlias,
                        PathFragments.emitTwoArgMethodCall(pred.condition(), "parentAlias", firstAlias));
                    case On.Lateral ignored -> throw new IllegalStateException(
                        "ParentCorrelation.OnParentJoin cannot wrap a lateral hop; its compact "
                        + "constructor rejects On.Lateral (a routine node is OnLateralArgs)");
                }
            }
            case ParentCorrelation.OnLateralArgs ignored ->
                sel.add(".crossJoin($T.lateral($L))\n", DSL, firstAlias);
        }
        for (int i = 1; i < path.size(); i++) {
            JoinStep bridging = path.get(i);
            String prevAlias = aliases.get(i - 1);
            switch (bridging) {
                case JoinStep.Hop hop -> sel.add("$L\n",
                    PathFragments.emitForwardBridging(hop, prevAlias, aliases.get(i)));
            }
        }
    }

    private static CodeBlock parentInputFieldLookup(ColumnRef parentCol, TableRef ownerTable) {
        return CodeBlock.of("parentInput.field($S, $T.$L.$L.getDataType())",
            parentCol.sqlName(),
            ownerTable.constantsClass(), ownerTable.javaFieldName(), parentCol.javaName());
    }

    /**
     * The WHERE fold: {@code DSL.noCondition()} AND-ed with each hop's filter (source alias
     * per the retired emitter's rule: the previous hop for hops 1..n, the correlation's parent
     * alias for hop 0, other arms classifier-unreachable and guarded) AND the coordinate's
     * condition glue off the terminal alias with the rows method's own {@code env.getArguments()}.
     * Hop filters stay this host's: join-path content, not condition content.
     */
    private static CodeBlock whereCondition(LauncherCommand row, LaunchSource.CorrelatedChain chain,
            PreludeBindings prelude) {
        var where = CodeBlock.builder();
        where.add("$T.noCondition()", DSL);
        List<JoinStep> path = chain.joinPath();
        for (int i = 0; i < path.size(); i++) {
            if (!(path.get(i) instanceof JoinStep.Hop hop)) continue;
            if (hop.filter() != null) {
                String srcAlias;
                if (i == 0) {
                    if (!(chain.correlation() instanceof ParentCorrelation.OnParentJoin)) {
                        throw new IllegalStateException(
                            "hop-0 filter reached the batched WHERE fold under "
                            + chain.correlation().getClass().getSimpleName() + "; the classifier lands "
                            + "any hop-0 filter on ParentCorrelation.OnParentJoin so a parent alias "
                            + "is in scope to bind the filter's source parameter");
                    }
                    srcAlias = "parentAlias";
                } else {
                    srcAlias = prelude.aliases().get(i - 1);
                }
                String tgtAlias = prelude.aliases().get(i);
                where.add("\n        .and($L)",
                    PathFragments.emitTwoArgMethodCall(hop.filter(), srcAlias, tgtAlias));
            }
        }
        if (row.where() != null) {
            where.add("\n        .and($L)",
                RootLauncherRenderer.glueExpression(row.where(), prelude.terminalAlias()));
        }
        return where.build();
    }

    private static ClassName className(no.sikt.graphitron.command.UnitRef ref) {
        return ClassName.get(ref.packageName(), ref.simpleName());
    }
}
