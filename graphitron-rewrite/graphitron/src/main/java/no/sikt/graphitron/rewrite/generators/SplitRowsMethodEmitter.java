package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.RowsMethodBody;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.ENV;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.LIST;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.RECORD;

/**
 * Builds the DataLoader rows-method MethodSpec for a {@link BatchKeyField} that emits a flat
 * correlated-batch SELECT keyed on a {@code VALUES (idx, parent_pk...)} derived table.
 *
 * <p>Emitted bodies have this shape:
 * <ol>
 *   <li>Empty-input short-circuit — returns {@code List.of()} without touching the DSL context.</li>
 *   <li>Parent-input {@code VALUES} table carrying {@code (idx, parent_pk...)} — one row per
 *       {@code keys[i]}. Rows are typed {@code Row<N+1><Integer, pkType1, pkType2, …>}, the
 *       corresponding typed {@link org.jooq.Table} carries {@link org.jooq.Record Record}&lt;N+1&gt;,
 *       and column access via {@code parentInput.fieldsRow().fieldK()} returns typed
 *       {@link org.jooq.Field Field}&lt;T&gt;. Arity is known at codegen time from
 *       {@link no.sikt.graphitron.rewrite.model.SourceKey#columns()}; generic array creation is the
 *       one unavoidable {@code @SuppressWarnings("unchecked")} per generated method.</li>
 *   <li>Key unpacking uses {@code k.field1()}…{@code k.fieldN()} — {@code Row1/Row2/…} expose
 *       their cells as typed {@code Field<T>} references (the inline {@code Field} jOOQ created
 *       when {@link GeneratorUtils#buildKeyExtraction} built the key via {@code DSL.row(record.get(col))}).
 *       The earlier plan's Decision 7 cited {@code value1()} calls, but those live on
 *       {@code Record1/Record2/…}, not on {@code Row} — {@code Row} is a schema construct, not
 *       a data carrier.</li>
 *   <li>FK chain aliases identical to the inline-projection path.</li>
 *   <li>{@code .select($fields + parentInput.fieldsRow().field1().as("__idx__"))} — the
 *       {@code __idx__} column drives the Java-side scatter, see {@link #IDX_COLUMN}.</li>
 *   <li>Explicit {@code ON} predicate joining the first FK hop to {@code parentInput} via
 *       {@code parentInput.fieldsRow().fieldK()} — typed {@code Field<T>}, matching the FK
 *       column's type in {@code .eq(...)}. Inherits the USING→ON lesson from
 *       {@link InlineLookupTableFieldEmitter} (junction tables re-expose the FK column and
 *       would collide under USING).</li>
 *   <li>{@code scatterByIdx(flat, keys.size())} — emitted once per fetcher class, see
 *       {@code TypeFetcherGenerator.buildScatterByIdxHelper}.</li>
 * </ol>
 *
 * <p>C1 supports list cardinality with {@code parentHoldsFk=false} only — the common
 * {@code @splitQuery} shape where the parent is the PK side and the target holds the FK. Single
 * cardinality and {@link JoinStep.ConditionJoin} paths emit runtime-throwing stubs with reasons
 * that name the required followup.
 *
 * <p>{@link ChildField.SplitLookupTableField} lands in C2; C1 throws at codegen time for that
 * branch so the missing step is visible.
 */
public final class SplitRowsMethodEmitter {

    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");
    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");

    /**
     * Returns the jOOQ {@code RowN}/{@code RecordN} class name for a given arity. jOOQ has typed
     * Row1..Row22 and Record1..Record22 classes; arities &gt;22 fall back to raw {@code RowN} and
     * {@code Record}. Parent PKs &gt;22 cols are rejected at codegen time.
     */
    private static ClassName rowClass(int arity) {
        return ClassName.get("org.jooq", "Row" + arity);
    }

    private static ClassName recordClass(int arity) {
        return ClassName.get("org.jooq", "Record" + arity);
    }

    // Synthetic SQL column aliases for the split-rows projection. The double-underscore wrapping
    // (__name__) is a deliberate collision-avoidance device, NOT the lazy dunder convention this
    // class otherwise avoids for Java locals: these names live in the result-set column namespace
    // alongside real table columns, which the consumer's DB schema controls. Wrapping in __ keeps
    // a synthetic alias from colliding with a real column the consumer happens to name `idx` or
    // `rn`. They reach generated code as string literals (.as("__idx__"), r.get("__rn__")), never
    // as Java identifiers, so the no-regression meta-test (which scans for dunder-prefixed
    // identifiers) correctly leaves them alone.

    /**
     * SELECT-projection alias for the parent-input {@code idx} column that drives the Java-side
     * scatter back to the originating parent row.
     */
    public static final String IDX_COLUMN = "__idx__";

    /**
     * SELECT-projection alias for the windowed {@code ROW_NUMBER()} column; the outer SELECT
     * filters {@code RN_COLUMN <= page.limit()} to enforce the per-partition page limit.
     */
    public static final String RN_COLUMN = "__rn__";

    private SplitRowsMethodEmitter() {}

    /**
     * Bindings produced by {@link #emitParentInputAndFkChain} that the three sibling rows-method
     * builders consume at their divergence points. Carries only what is re-derived in more than
     * one sibling; per-sibling locals (typed Row/Record/Table type names, the projection list,
     * etc.) stay in the sibling.
     *
     * <p>{@code joinOnCols} is the column list the rows-method's {@code JOIN parentInput ... ON ...}
     * predicate matches against {@code firstAlias}: on the catalog-FK path, that's
     * {@link JoinStep.FkJoin#sourceColumns()} (FK-holder side, terminal for list cardinality); on
     * the lifter path, {@link JoinStep.LiftedHop#targetColumns()} (the DataLoader key tuple IS the
     * target-column tuple by {@code LiftedHop} construction); on the
     * @sourceRow + @reference path, {@link JoinStep.FkJoin#sourceColumns()} again. The prelude resolves
     * this fork once via a sealed switch and exports the ready list; the consumer iterates without
     * re-switching.
     *
     * <p>{@code joinOnParentCols} is parallel to {@code joinOnCols}: index {@code i} is the parent
     * column that {@code joinOnCols.get(i)} references. On the catalog-FK path that is
     * {@link JoinStep.FkJoin#targetColumns()} (the FK's parent-side referenced columns, paired
     * with {@code sourceColumns} by FK declaration order); on the lifter path it is the same list
     * as {@code joinOnCols} (the DataLoader key tuple IS the target-column tuple). Consumers
     * resolve the {@code parentInput.field(...)} reference for each predicate slot by
     * sqlName + Java type, sidestepping any positional mismatch between FK column ordering and
     * the parent VALUES table's aliasing order.
     */
    private record PreludeBindings(
        List<String> aliases,
        String terminalAlias,
        String firstAlias,
        // For ParentCorrelation.OnFkSlots: firstAlias (the first hop's target table).
        // For ParentCorrelation.OnConditionJoin: parentAlias (the @table-bound parent declared
        // in the prelude). The {@code .join(parentInput).on(...)} predicate's LHS reads this
        // alias paired with {@link #joinOnCols}; the parent-input field lookup uses {@link
        // #joinOnParentCols} for sqlName + Java type. The two arms collapse onto one accessor
        // so the buildList/Single/Connection emitter sites don't fork.
        String joinOnAlias,
        List<ColumnRef> joinOnCols,
        List<ColumnRef> joinOnParentCols,
        TypeName keyElement
    ) {}

    /**
     * Emits the five-act prelude shared by {@link #buildListMethod}, {@link #buildSingleMethod},
     * and {@link #buildConnectionMethod}: empty-input short-circuit, {@code dsl} resolution,
     * typed {@code parentRows[]} VALUES with its {@code @SuppressWarnings} cast,
     * {@code parentInput} derived-table aliasing, and the FK-chain alias declarations. Mutates
     * {@code body} and returns the bindings each sibling needs at its divergence point.
     *
     * <p>Single-cardinality callers pass a single-hop {@code joinPath}; the FK-chain loop emits
     * one declaration in that case.
     */
    private static PreludeBindings emitParentInputAndFkChain(
            TypeFetcherEmissionContext ctx,
            CodeBlock.Builder body,
            String fieldName,
            SourceKey sourceKey,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<JoinStep> joinPath,
            ParentCorrelation parentCorrelation) {
        TableRef terminalTable = returnType.table();

        // Side-aware column list: ColumnRead carries parent-side FK columns (catalog-FK arm);
        // SourceRowsCall and AccessorCall carry the join target columns (target side via the
        // LiftedHop / FkJoin chain). All four produce RowN<...> of the same Java types as the
        // JOIN target columns. The SourceKey parameter encodes "only the four prelude-reachable
        // shapes reach this site" structurally; the @service Readers are unreachable here by
        // entry-point construction (only the four BatchKeyField permits with sourceKey() routed
        // through buildList/Single/Connection reach the prelude).
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
            parentRowTypeArgs[i + 1] = ClassName.bestGuess(pkCols.get(i).columnClass());
        }
        TypeName parentRowType = ParameterizedTypeName.get(rowClass(parentRowArity), parentRowTypeArgs);
        TypeName parentRecordType = ParameterizedTypeName.get(recordClass(parentRowArity), parentRowTypeArgs);
        TypeName parentInputTableType = ParameterizedTypeName.get(TABLE, parentRecordType);

        List<String> aliases = JoinPathEmitter.generateAliases(joinPath, terminalTable);
        String terminalAlias = aliases.get(aliases.size() - 1);
        String firstAlias = aliases.get(0);
        // Classifier contract: joinPath is non-empty. The first step is either an FK-style
        // hop (FkJoin or LiftedHop, both WithTarget — pairable slots) or a ConditionJoin
        // (no slots; correlation is the method call). The sealed switch on parentCorrelation
        // routes both arms uniformly: OnFkSlots reads the slot pairs as before; OnConditionJoin
        // declares the parent-alias table local and routes parentInput's JOIN through the
        // parent's own PK columns.
        String joinOnAlias;
        List<ColumnRef> joinOnCols;
        List<ColumnRef> joinOnParentCols;
        switch (parentCorrelation) {
            case ParentCorrelation.OnFkSlots fk -> {
                JoinStep.WithTarget firstWithTarget = fk.firstHop();
                joinOnAlias = firstAlias;
                joinOnCols = firstWithTarget.targetSideColumns();
                joinOnParentCols = firstWithTarget.sourceSideColumns();
            }
            case ParentCorrelation.OnConditionJoin cj -> {
                // ParentInput joins on the parent table's own PK columns: the predicate is
                // parentAlias.<pkCol> = parentInput.field("<pkCol.sqlName>"). The DataLoader
                // key tuple still IS the parent-PK tuple, so the cols on both sides of the
                // predicate are the same ColumnRef set — only the alias to put them on differs.
                joinOnAlias = "parentAlias";
                joinOnCols = cj.parentPkCols();
                joinOnParentCols = cj.parentPkCols();
            }
        }

        // Empty-input short-circuit and DSLContext local are emitted by RowsMethodSkeleton's SQL
        // framing; this prelude picks up after both.

        // Parent-input VALUES rows — fully typed. One Row<N+1><Integer, pkType1, …> per key[i].
        // Generic array creation is the one unavoidable unchecked cast: Java forbids
        //   new Row2<Integer, Integer>[n]
        // so we cast a raw Row<N+1>[] up to the typed array. Scoped to this one line.
        body.add("@$T({$S, $S})\n", ClassName.get("java.lang", "SuppressWarnings"), "unchecked", "rawtypes");
        body.addStatement("$T[] parentRows = ($T[]) new $T[keys.size()]",
            parentRowType, parentRowType, rowClass(parentRowArity));
        body.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        body.addStatement("$T k = keys.get(i)", keyElement);
        var rowArgs = CodeBlock.builder();
        rowArgs.add("$T.inline(i)", DSL);
        // RowN-keyed arms (RowKeyed, LifterLeafKeyed, LifterPathKeyed) construct keys via DSL.row(value, ...)
        // where field<N>() returns a bind-parameter Field<T> wrapping the value, so passing
        // k.field<N>() into the parent VALUES row renders the value (not a column reference).
        //
        // RecordN-keyed arms (AccessorKeyedSingle / AccessorKeyedMany) construct keys via
        // record.into(table.col, ...): field<N>() returns the source-table column reference,
        // which would leak into the VALUES table as the column name instead of the value. We
        // pull the scalar via value<N>() and wrap it in DSL.val(...) so the argument typechecks
        // against jOOQ's Field-based DSL.row overload alongside the inline-i first argument.
        boolean isAccessor = sourceKey.reader() instanceof SourceKey.Reader.AccessorCall;
        for (int i = 0; i < pkCols.size(); i++) {
            if (isAccessor) {
                rowArgs.add(", $T.val(k.value$L())", DSL, i + 1);
            } else {
                rowArgs.add(", k.field$L()", i + 1);
            }
        }
        body.addStatement("parentRows[i] = $T.row($L)", DSL, rowArgs.build());
        body.endControlFlow();

        // VALUES derived-table alias: "parentInput", "idx", pk_col1_sqlName, pk_col2_sqlName, …
        var parentInputAlias = CodeBlock.builder();
        parentInputAlias.add("$S, $S", "parentInput", "idx");
        for (var col : pkCols) {
            parentInputAlias.add(", $S", col.sqlName());
        }
        body.addStatement("$T parentInput = $T.values(parentRows).as($L)",
            parentInputTableType, DSL, parentInputAlias.build());

        // Hop aliases — one declaration per hop. Reads target accessors uniformly through
        // HasTargetTable so FkJoin, LiftedHop, and ConditionJoin all surface their pre-resolved
        // targetTable without an arm-specific cast (post-R232 ConditionJoin carries a resolved
        // TableRef; see BuildContext.resolveConditionJoinTarget).
        for (int i = 0; i < joinPath.size(); i++) {
            JoinStep.HasTargetTable step = (JoinStep.HasTargetTable) joinPath.get(i);
            ClassName jooqTableClass = step.targetTable().tableClass();
            body.addStatement("$T $L = $T.$L.as($S)",
                jooqTableClass, aliases.get(i), step.targetTable().constantsClass(), step.targetTable().javaFieldName(),
                fieldName + "_" + aliases.get(i));
        }

        // OnConditionJoin: declare the @table-bound parent alias the rows-method JOINs against
        // via the condition method, and against which parentInput pairs on parent-PK columns.
        // OnFkSlots reuses firstAlias as the join-on alias and needs no extra declaration.
        if (parentCorrelation instanceof ParentCorrelation.OnConditionJoin cj) {
            TableRef parentTable = cj.parentTable();
            body.addStatement("$T parentAlias = $T.$L.as($S)",
                parentTable.tableClass(), parentTable.constantsClass(), parentTable.javaFieldName(),
                fieldName + "_parent");
        }

        return new PreludeBindings(aliases, terminalAlias, firstAlias, joinOnAlias, joinOnCols, joinOnParentCols, keyElement);
    }

    // -----------------------------------------------------------------------
    // SplitTableField
    // -----------------------------------------------------------------------

    /**
     * Builds the rows-method for a {@link ChildField.SplitTableField}. Sibling entry points
     * {@link #buildForSplitLookupTable}, {@link #buildForRecordTable},
     * {@link #buildForRecordLookupTable} cover the other three batched-field shapes; each
     * caller in {@code TypeFetcherGenerator} already has the concrete field type, so no
     * capability-typed dispatcher is needed at this seam.
     */
    static MethodSpec buildForSplitTable(TypeFetcherEmissionContext ctx, ChildField.SplitTableField stf, String outputPackage) {
        java.util.function.Function<CodeBlock, RowsMethodBody> permit = RowsMethodBody.SqlSplitTable::new;
        if (stf.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Single) {
            return buildSingleMethod(
                ctx, stf.name(), stf.rowsMethodName(), stf.returnType(),
                stf.joinPath(), stf.filters(), stf.sourceKey(),
                stf.parentCorrelation(),
                outputPackage, permit);
        }
        if (stf.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
            return buildConnectionMethod(
                ctx, stf.name(), stf.rowsMethodName(), stf.returnType(),
                stf.joinPath(), stf.filters(), stf.sourceKey(), stf.orderBy(), conn,
                stf.parentCorrelation(),
                outputPackage, permit);
        }
        return buildListMethod(
            ctx, stf.name(), stf.rowsMethodName(), stf.returnType(),
            stf.joinPath(), stf.filters(), stf.sourceKey(),
            /* lookupMapping */ null,
            stf.parentCorrelation(),
            outputPackage, permit);
    }

    // -----------------------------------------------------------------------
    // SplitLookupTableField (C2)
    // -----------------------------------------------------------------------

    /** See {@link #buildForSplitTable} for the entry-point convention. */
    static MethodSpec buildForSplitLookupTable(TypeFetcherEmissionContext ctx, ChildField.SplitLookupTableField slf, String outputPackage) {
        return buildListMethod(
            ctx, slf.name(), slf.rowsMethodName(), slf.returnType(),
            slf.joinPath(), slf.filters(), slf.sourceKey(),
            slf.lookupMapping(),
            slf.parentCorrelation(),
            outputPackage,
            RowsMethodBody.SqlSplitLookupTable::new);
    }

    // -----------------------------------------------------------------------
    // RecordTableField
    // -----------------------------------------------------------------------

    /** See {@link #buildForSplitTable} for the entry-point convention. */
    static MethodSpec buildForRecordTable(TypeFetcherEmissionContext ctx, ChildField.RecordTableField rtf, String outputPackage) {
        // RecordTableField fields whose rows-method emits 1 record per key (today, only
        // AccessorKeyedMany: each accessor-derived element-PK key maps to exactly one
        // terminal record) route through buildSingleMethod, which produces the flat join +
        // scatterSingleByIdx shape required by the loader.loadMany dispatch (loader value
        // type Record, returning List<Record> 1:1 with keys, not List<List<Record>>). The
        // capability fold here matches TypeFetcherGenerator's scatterSingleByIdx helper-emission
        // gate; both ask the same uniform question of multiple variants.
        java.util.function.Function<CodeBlock, RowsMethodBody> permit = RowsMethodBody.SqlRecordTable::new;
        if (rtf.emitsSingleRecordPerKey()) {
            return buildSingleMethod(
                ctx, rtf.name(), rtf.rowsMethodName(), rtf.returnType(),
                rtf.joinPath(), rtf.filters(), rtf.sourceKey(),
                rtf.parentCorrelation(),
                outputPackage, permit);
        }
        return buildListMethod(
            ctx, rtf.name(), rtf.rowsMethodName(), rtf.returnType(),
            rtf.joinPath(), rtf.filters(), rtf.sourceKey(),
            /* lookupMapping */ null,
            rtf.parentCorrelation(),
            outputPackage, permit);
    }

    // -----------------------------------------------------------------------
    // RecordTableMethodField
    // -----------------------------------------------------------------------

    /**
     * Rows-method for {@link ChildField.RecordTableMethodField}: the DTO-parent sibling of
     * {@link #buildForRecordTable}. Identical parent-VALUES + flat-JOIN-on-FK shape; the only
     * difference is that the terminal table is materialised by calling the developer's static
     * {@code @tableMethod} (returning a generated jOOQ table) rather than referencing
     * {@code Tables.<X>} directly. Single-hop {@link JoinStep.FkJoin} only, mirroring the
     * table-parent {@link ChildField.TableMethodField} emit's shipped shape (R43 commit 3);
     * multi-hop FK paths and empty joinPaths surface a runtime
     * {@link UnsupportedOperationException}. {@link JoinStep.ConditionJoin} first-hops are
     * caught at parse time by {@link no.sikt.graphitron.rewrite.FieldBuilder}'s
     * {@code buildParentCorrelation} call (the {@code @record}-parent has no {@code @table} to
     * anchor the condition method's source argument, so the path AUTHOR_ERRORs upstream and
     * never reaches this emitter).
     */
    static MethodSpec buildForRecordTableMethod(TypeFetcherEmissionContext ctx,
            ChildField.RecordTableMethodField rtmf, String outputPackage) {
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        TypeName keyElement = rtmf.sourceKey().keyElementType();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);
        boolean singleRecordPerKey = rtmf.emitsSingleRecordPerKey();
        TypeName outerReturn = singleRecordPerKey ? listOfRecord : listOfListOfRecord;

        List<JoinStep> path = rtmf.joinPath();
        // ConditionJoin first-hop on @record-parent is caught upstream by FieldBuilder's
        // parentCorrelation synthesis (no parent @table to anchor); the predicate below only
        // covers the pre-existing R43 limits on RecordTableMethodField (empty + multi-hop).
        boolean unsupportedPath = path.isEmpty() || path.size() > 1;
        if (unsupportedPath) {
            String shapeLabel = path.isEmpty()
                ? "empty joinPath"
                : "multi-hop join path";
            var stub = CodeBlock.builder()
                .addStatement("throw new $T($S)",
                    UnsupportedOperationException.class,
                    "RecordTableMethodField with " + shapeLabel + " is not yet emitted — only single-hop "
                        + "FK paths ship in R43 commit 5")
                .build();
            return RowsMethodSkeleton.build(
                rtmf.rowsMethodName(),
                outerReturn,
                keysListType,
                ctx.graphitronContextCall(),
                new RowsMethodBody.SqlRecordTableMethod(stub));
        }

        var body = CodeBlock.builder();
        emitRecordTableMethodBody(ctx, body, rtmf, outputPackage, singleRecordPerKey);

        return RowsMethodSkeleton.build(
            rtmf.rowsMethodName(),
            outerReturn,
            keysListType,
            ctx.graphitronContextCall(),
            new RowsMethodBody.SqlRecordTableMethod(body.build()));
    }

    /**
     * Inlines the body for the single-hop FK shape: parent {@code VALUES} rows + developer's
     * {@code @tableMethod} call + flat join + scatter by idx. Kept separate from
     * {@link #buildForRecordTableMethod} so the runtime-stub fallback in the entry method stays
     * close to the shape check.
     */
    private static void emitRecordTableMethodBody(TypeFetcherEmissionContext ctx,
            CodeBlock.Builder body, ChildField.RecordTableMethodField rtmf, String outputPackage,
            boolean singleRecordPerKey) {
        var returnType = rtmf.returnType();
        var sourceKey = rtmf.sourceKey();
        String fieldName = rtmf.name();

        ClassName typeClass = ClassName.get(
            outputPackage + ".types",
            returnType.returnTypeName());
        var names = GeneratorUtils.ResolvedTableNames.of(returnType.table(),
            returnType.returnTypeName(), outputPackage);

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
            parentRowTypeArgs[i + 1] = ClassName.bestGuess(pkCols.get(i).columnClass());
        }
        TypeName parentRowType = ParameterizedTypeName.get(rowClass(parentRowArity), parentRowTypeArgs);
        TypeName parentRecordType = ParameterizedTypeName.get(recordClass(parentRowArity), parentRowTypeArgs);
        TypeName parentInputTableType = ParameterizedTypeName.get(TABLE, parentRecordType);

        body.add("@$T({$S, $S})\n", ClassName.get("java.lang", "SuppressWarnings"), "unchecked", "rawtypes");
        body.addStatement("$T[] parentRows = ($T[]) new $T[keys.size()]",
            parentRowType, parentRowType, rowClass(parentRowArity));
        body.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        body.addStatement("$T k = keys.get(i)", keyElement);
        var rowArgs = CodeBlock.builder();
        rowArgs.add("$T.inline(i)", DSL);
        boolean isAccessor = sourceKey.reader() instanceof SourceKey.Reader.AccessorCall;
        for (int i = 0; i < pkCols.size(); i++) {
            if (isAccessor) {
                rowArgs.add(", $T.val(k.value$L())", DSL, i + 1);
            } else {
                rowArgs.add(", k.field$L()", i + 1);
            }
        }
        body.addStatement("parentRows[i] = $T.row($L)", DSL, rowArgs.build());
        body.endControlFlow();

        var parentInputAlias = CodeBlock.builder();
        parentInputAlias.add("$S, $S", "parentInput", "idx");
        for (var col : pkCols) {
            parentInputAlias.add(", $S", col.sqlName());
        }
        body.addStatement("$T parentInput = $T.values(parentRows).as($L)",
            parentInputTableType, DSL, parentInputAlias.build());

        // Terminal table: invoke the developer's static @tableMethod. Aliased so the parent-input
        // JOIN can address its columns via the local. The leading-table parameter was retired
        // in R43 commit 1; ArgCallEmitter is called with null for the table expression.
        var methodClass = ClassName.bestGuess(rtmf.method().className());
        String conditionsClassName = outputPackage + ".conditions."
            + rtmf.parentTypeName() + QueryConditionsGenerator.CLASS_NAME_SUFFIX;
        String terminalAlias = fieldName + "_terminal";
        body.addStatement("$T $L = $T.$L($L).as($S)",
            names.jooqTableClass(), terminalAlias,
            methodClass, rtmf.method().methodName(),
            ArgCallEmitter.buildMethodBackedCallArgs(ctx, rtmf.method(), null, conditionsClassName),
            terminalAlias);

        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST, wildField);
        body.addStatement("$T selectFields = new $T<>($T.$$fields(env.getSelectionSet(), $L, env))",
            listOfField, ARRAY_LIST, typeClass, terminalAlias);
        body.addStatement("selectFields.add(parentInput.field(0, $T.class).as($S))",
            Integer.class, IDX_COLUMN);

        // JOIN parentInput on FK columns. The single FkJoin's slots pair source (parent) and
        // target (developer table) columns; the parent-side column lookup goes by sqlName + Java
        // type (sidestepping potential @node ordering mismatches).
        JoinStep.FkJoin firstHop = (JoinStep.FkJoin) rtmf.joinPath().get(0);
        var onCond = CodeBlock.builder();
        int slotIdx = 0;
        for (var slot : firstHop.slots()) {
            if (slotIdx > 0) onCond.add(".and(");
            ColumnRef parentCol = slot.sourceSide();
            ClassName parentColType = ClassName.bestGuess(parentCol.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($S, $T.class))",
                terminalAlias,
                slot.targetSide().javaName(),
                parentCol.sqlName(), parentColType);
            if (slotIdx > 0) onCond.add(")");
            slotIdx++;
        }

        body.add("$T<$T> flat = dsl\n", ClassName.get("org.jooq", "Result"), RECORD);
        body.indent();
        body.add(".select(selectFields)\n");
        body.add(".from($L)\n", terminalAlias);
        body.add(".join(parentInput).on($L)\n", onCond.build());
        body.add(".fetch();\n");
        body.unindent();

        body.addStatement("return $L(flat, keys.size())",
            singleRecordPerKey ? "scatterSingleByIdx" : "scatterByIdx");
    }

    // -----------------------------------------------------------------------
    // RecordLookupTableField
    // -----------------------------------------------------------------------

    /** See {@link #buildForSplitTable} for the entry-point convention. */
    static MethodSpec buildForRecordLookupTable(TypeFetcherEmissionContext ctx, ChildField.RecordLookupTableField rltf, String outputPackage) {
        // Rows-method body is identical to SplitLookupTableField's — same SourceKey
        // (Wrap.Row + ColumnRead) + LookupMapping shape, so buildListMethod handles both. The
        // record-parent divergence (backing-object accessor vs jOOQ-table-row accessor for key
        // extraction) lives above this seam, in TypeFetcherGenerator.buildRecordBasedDataFetcher.
        return buildListMethod(
            ctx, rltf.name(), rltf.rowsMethodName(), rltf.returnType(),
            rltf.joinPath(), rltf.filters(), rltf.sourceKey(),
            rltf.lookupMapping(),
            rltf.parentCorrelation(),
            outputPackage,
            RowsMethodBody.SqlRecordLookupTable::new);
    }


    /**
     * Shared body emitter for list-cardinality Split* rows methods. For
     * {@link ChildField.SplitTableField} pass {@code lookupMapping = null}; for
     * {@link ChildField.SplitLookupTableField} pass its mapping and the emitter adds a second
     * VALUES derived-table JOIN narrowing on the {@code @lookupKey} args.
     */
    private static MethodSpec buildListMethod(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            String rowsMethodName,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<JoinStep> joinPath,
            List<WhereFilter> filters,
            SourceKey sourceKey,
            LookupMapping lookupMapping,
            ParentCorrelation parentCorrelation,
            String outputPackage,
            java.util.function.Function<CodeBlock, RowsMethodBody> permitFactory) {
        ClassName typeClass = ClassName.get(
            outputPackage + ".types",
            returnType.returnTypeName());

        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);

        var body = CodeBlock.builder();
        PreludeBindings p = emitParentInputAndFkChain(ctx,
            body, fieldName, sourceKey, returnType, joinPath, parentCorrelation);
        List<JoinStep> path = joinPath;
        List<String> aliases = p.aliases();
        String terminalAlias = p.terminalAlias();
        String firstAlias = p.firstAlias();
        String joinOnAlias = p.joinOnAlias();
        List<ColumnRef> joinOnCols = p.joinOnCols();
        List<ColumnRef> joinOnParentCols = p.joinOnParentCols();
        TypeName keyElement = p.keyElement();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);

        // Projection: $fields(env.getSelectionSet(), terminalAlias, env) + idx.as("__idx__").
        // env.getSelectionSet() is the child-selection for the Split field itself — exactly what
        // a SelectedField.getSelectionSet() would return, so the rows method signature does not
        // need a separate SelectedField parameter. See the "dropped sel parameter" commit message.
        //
        // Typed idx access: parentInput.field(0, Integer.class) → Field<Integer>. Table.fieldsRow()
        // inherits from Fields and returns untyped Row (it's not overridden on Table<RecordN> with
        // a typed return, despite RecordN itself exposing typed fieldsRow). The typed-by-index
        // Fields.field(int, Class<T>) is the idiomatic jOOQ alternative and preserves type safety.
        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST, wildField);
        body.addStatement("$T selectFields = new $T<>($T.$$fields(env.getSelectionSet(), $L, env))",
            listOfField, ARRAY_LIST, typeClass, terminalAlias);
        body.addStatement("selectFields.add(parentInput.field(0, $T.class).as($S))",
            Integer.class, IDX_COLUMN);

        // Lookup-input VALUES (SplitLookupTableField only). Uses the env-based helper shape:
        // args live on env.getArgument(name) for a Split fetcher (not on a child SelectedField
        // as in the inline-projection path). The helper method name follows the
        // <fieldName>InputRows convention used by the inline-projection path.
        String lookupInputAlias = fieldName + "Input";
        if (lookupMapping instanceof LookupMapping.ColumnMapping columnMapping) {
            List<ColumnRef> lookupCols = columnMapping.slotColumns();
            // Typed Row<M+1> / Record<M+1> for lookupInput — idx + one cell per @lookupKey
            // slot. Arity known at codegen time; the cap is enforced inside LookupValuesJoinEmitter
            // (which emits the helper this call consumes). DSL.values(Row<M+1>...) returns
            // Table<Record<M+1>> — typed through to field access by index or name.
            int lookupArity = lookupCols.size() + 1;
            TypeName[] lookupTypeArgs = new TypeName[lookupArity];
            lookupTypeArgs[0] = ClassName.get(Integer.class);
            for (int i = 0; i < lookupCols.size(); i++) {
                lookupTypeArgs[i + 1] = ClassName.bestGuess(lookupCols.get(i).columnClass());
            }
            TypeName lookupRowType = ParameterizedTypeName.get(rowClass(lookupArity), lookupTypeArgs);
            TypeName lookupRecordType = ParameterizedTypeName.get(recordClass(lookupArity), lookupTypeArgs);
            TypeName lookupInputTableType = ParameterizedTypeName.get(TABLE, lookupRecordType);
            body.addStatement("$T[] lookupRows = $LInputRows(env, $L)", lookupRowType, fieldName, terminalAlias);
            // Empty lookup input → every parent gets an empty list; short-circuit before building
            // the VALUES table (jOOQ rejects empty Row<M+1>[] → DSL.values).
            body.beginControlFlow("if (lookupRows.length == 0)");
            body.addStatement("return emptyScatter(keys.size())");
            body.endControlFlow();
            // Labels: ("fieldNameInput", "idx", lookupCol1.sqlName, ...).
            var lookupAliasArgs = CodeBlock.builder();
            lookupAliasArgs.add("$S, $S", lookupInputAlias, "idx");
            for (var col : lookupCols) {
                lookupAliasArgs.add(", $S", col.sqlName());
            }
            body.addStatement("$T lookupInput = $T.values(lookupRows).as($L)",
                lookupInputTableType, DSL, lookupAliasArgs.build());
        }

        // Flat SELECT: FROM terminal, JOIN bridging hops back toward step 0, JOIN parentInput
        // on first-hop source columns eq parent PK via parentInput.field(sqlName).
        var sel = CodeBlock.builder();
        sel.add("$T<$T> flat = dsl\n", ClassName.get("org.jooq", "Result"), RECORD);
        sel.indent();
        sel.add(".select(selectFields)\n");
        sel.add(".from($L)\n", terminalAlias);
        // Bridging hops: terminal back to step 0. Dispatches on step identity — FK hops use
        // .onKey(FK), condition hops use .on(method(prevAlias, alias)). LiftedHop never appears
        // in an @reference-composed multi-hop path (single-hop terminal only), so the throw is
        // an unreachable-by-construction sentinel.
        for (int i = path.size() - 1; i >= 1; i--) {
            JoinStep bridging = path.get(i);
            String prevAlias = aliases.get(i - 1);
            switch (bridging) {
                case JoinStep.FkJoin fk -> sel.add(".join($L).onKey($T.$L)\n",
                    prevAlias, fk.fk().keysClass(), fk.fk().constantName());
                case JoinStep.ConditionJoin cj -> sel.add(".join($L).on($L)\n",
                    prevAlias, JoinPathEmitter.emitTwoArgMethodCall(cj.condition(), aliases.get(i), prevAlias));
                case JoinStep.LiftedHop ignored -> throw new IllegalStateException(
                    "LiftedHop should not appear at bridging position; @reference-composed paths "
                    + "are FkJoin / ConditionJoin chains, lifter shapes are single-hop");
            }
        }
        // OnConditionJoin: bring parentAlias into scope via the step-0 condition method,
        // pairing parentAlias with firstAlias (= aliases[0], the ConditionJoin's resolved target).
        // OnFkSlots needs no extra JOIN here — parentInput pairs directly with firstAlias.
        if (parentCorrelation instanceof ParentCorrelation.OnConditionJoin cj) {
            sel.add(".join(parentAlias).on($L)\n",
                JoinPathEmitter.emitTwoArgMethodCall(cj.firstHop().condition(), "parentAlias", firstAlias));
        }
        // JOIN parentInput on the carrier's parent-correlation columns. For OnFkSlots, joinOnAlias
        // is firstAlias and the predicate pairs slot.targetSide()/slot.sourceSide(). For
        // OnConditionJoin, joinOnAlias is parentAlias and the predicate pairs parent-PK on both
        // sides (DataLoader key tuple IS parent-PK tuple). The single accessor (joinOnAlias +
        // joinOnCols / joinOnParentCols) means buildList/Single/Connection don't fork here.
        var onCond = CodeBlock.builder();
        for (int i = 0; i < joinOnCols.size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef parentCol = joinOnParentCols.get(i);
            ClassName parentColType = ClassName.bestGuess(parentCol.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($S, $T.class))",
                joinOnAlias,
                joinOnCols.get(i).javaName(),
                parentCol.sqlName(), parentColType);
            if (i > 0) onCond.add(")");
        }
        sel.add(".join(parentInput).on($L)\n", onCond.build());

        // Lookup-input JOIN (SplitLookupTableField only). ON predicate uses typed
        // lookupInput.field(i+1, ColType.class) so the .eq against terminalAlias.COL matches
        // types directly. Position mapping inside lookupInput: index 0 is idx, indices 1..M
        // are the lookup columns in LookupMapping order. Same USING-vs-ON reasoning as the
        // parent-input JOIN.
        if (lookupMapping instanceof LookupMapping.ColumnMapping columnMapping2) {
            var lookupOnCond = CodeBlock.builder();
            List<ColumnRef> lookupCols = columnMapping2.slotColumns();
            for (int i = 0; i < lookupCols.size(); i++) {
                if (i > 0) lookupOnCond.add(".and(");
                var col = lookupCols.get(i);
                ClassName colType = ClassName.bestGuess(col.columnClass());
                lookupOnCond.add("$L.$L.eq(lookupInput.field($L, $T.class))",
                    terminalAlias, col.javaName(),
                    i + 1, colType);
                if (i > 0) lookupOnCond.add(")");
            }
            sel.add(".join(lookupInput).on($L)\n", lookupOnCond.build());
        }

        // WHERE: per-hop whereFilters + field-level filters. Only FkJoin hops carry a
        // whereFilter; LiftedHop (lifter path) holds the target table + key columns and has no
        // FK-side filter to apply, so the loop skips it.
        var where = CodeBlock.builder();
        where.add("$T.noCondition()", DSL);
        for (int i = 0; i < path.size(); i++) {
            if (!(path.get(i) instanceof JoinStep.FkJoin hop)) continue;
            if (hop.whereFilter() != null) {
                String srcAlias = i == 0 ? firstAlias : aliases.get(i - 1);
                String tgtAlias = aliases.get(i);
                where.add(".and($L)",
                    JoinPathEmitter.emitTwoArgMethodCall(hop.whereFilter(), srcAlias, tgtAlias));
            }
        }
        for (WhereFilter f : filters) {
            where.add(".and($T.$L($L))",
                ClassName.bestGuess(f.className()), f.methodName(),
                ArgCallEmitter.buildCallArgs(ctx, f.callParams(), f.className(), terminalAlias));
        }
        sel.add(".where($L)\n", where.build());
        sel.add(".fetch();\n");
        sel.unindent();
        body.add(sel.build());

        body.addStatement("return scatterByIdx(flat, keys.size())");

        return RowsMethodSkeleton.build(
            rowsMethodName,
            listOfListOfRecord,
            keysListType,
            ctx.graphitronContextCall(),
            permitFactory.apply(body.build()));
    }

    /**
     * Single-cardinality sibling of {@link #buildListMethod} for {@link ChildField.SplitTableField}.
     * Classifier contract: single-hop, parent-holds-FK ({@link ChildField.SplitTableField}'s
     * {@code sourceKey} carries the parent's FK columns per
     * {@code FieldBuilder.deriveSplitQuerySource}). Emits a flat
     * {@code terminal JOIN parentInput ON terminal.pk = parentInput.fk_value} SELECT that returns
     * {@code List<Record>} indexed 1:1 with {@code keys} (nulls where no match).
     *
     * <p>The JOIN column reference differs from list-cardinality: list-cardinality's
     * {@code firstHop.sourceColumns()} sit on the target (terminal) table (the FK-holder is the
     * child); single-cardinality's {@code sourceColumns()} sit on the <em>parent</em>, so the
     * emitter uses {@code firstHop.targetColumns()} to address the column on {@code firstAlias}
     * and {@code firstHop.sourceColumns()} (FkJoin) or the same {@code targetColumns()}
     * (LiftedHop) as the parent-side column whose value drives the {@code parentInput.field(...)}
     * lookup. The lookup is by sqlName + Java type rather than positional index, so an
     * {@code @node(keyColumns: [...])} order that differs from the FK column order does not
     * mis-pair column types.
     */
    private static MethodSpec buildSingleMethod(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            String rowsMethodName,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<JoinStep> joinPath,
            List<WhereFilter> filters,
            SourceKey sourceKey,
            ParentCorrelation parentCorrelation,
            String outputPackage,
            java.util.function.Function<CodeBlock, RowsMethodBody> permitFactory) {
        ClassName typeClass = ClassName.get(
            outputPackage + ".types",
            returnType.returnTypeName());

        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);

        var body = CodeBlock.builder();
        // Classifier guarantees single-hop (§1c rejects multi-hop single-cardinality, the lifter
        // path is single-hop by type construction, and the accessor-derived path builds a
        // [LiftedHop] joinPath in FieldBuilder), so the shared prelude's FK-chain loop emits
        // exactly one declaration.
        PreludeBindings p = emitParentInputAndFkChain(ctx,
            body, fieldName, sourceKey, returnType, joinPath, parentCorrelation);
        String firstAlias = p.firstAlias();
        String joinOnAlias = p.joinOnAlias();
        List<ColumnRef> joinOnCols = p.joinOnCols();
        List<ColumnRef> joinOnParentCols = p.joinOnParentCols();
        TypeName keyElement = p.keyElement();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);

        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST, wildField);
        body.addStatement("$T selectFields = new $T<>($T.$$fields(env.getSelectionSet(), $L, env))",
            listOfField, ARRAY_LIST, typeClass, firstAlias);
        body.addStatement("selectFields.add(parentInput.field(0, $T.class).as($S))",
            Integer.class, IDX_COLUMN);

        // JOIN parentInput on the carrier's parent-correlation columns. The prelude bindings
        // collapse the OnFkSlots / OnConditionJoin fork: joinOnAlias is firstAlias for FK first
        // hops or parentAlias for condition first hops; joinOnCols + joinOnParentCols carry the
        // matching column lists. The parentInput field is resolved by sqlName + Java type rather
        // than positional index, sidestepping @node(keyColumns: [...]) vs FK column ordering
        // mismatches.
        var onCond = CodeBlock.builder();
        for (int i = 0; i < joinOnCols.size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef parentCol = joinOnParentCols.get(i);
            ClassName colType = ClassName.bestGuess(parentCol.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($S, $T.class))",
                joinOnAlias,
                joinOnCols.get(i).javaName(),
                parentCol.sqlName(), colType);
            if (i > 0) onCond.add(")");
        }

        var sel = CodeBlock.builder();
        sel.add("$T<$T> flat = dsl\n", ClassName.get("org.jooq", "Result"), RECORD);
        sel.indent();
        sel.add(".select(selectFields)\n");
        sel.add(".from($L)\n", firstAlias);
        // OnConditionJoin: bring parentAlias into scope via the step-0 condition method,
        // pairing parentAlias against firstAlias (the ConditionJoin's resolved target = the
        // terminal alias on the single-hop path).
        if (parentCorrelation instanceof ParentCorrelation.OnConditionJoin cj) {
            sel.add(".join(parentAlias).on($L)\n",
                JoinPathEmitter.emitTwoArgMethodCall(cj.firstHop().condition(), "parentAlias", firstAlias));
        }
        sel.add(".join(parentInput).on($L)\n", onCond.build());

        var where = CodeBlock.builder();
        where.add("$T.noCondition()", DSL);
        // whereFilter is FkJoin-only (the accessor / lifter / condition paths carry no per-step WHERE).
        if (joinPath.get(0) instanceof JoinStep.FkJoin fk && fk.whereFilter() != null) {
            where.add(".and($L)",
                JoinPathEmitter.emitTwoArgMethodCall(fk.whereFilter(), firstAlias, firstAlias));
        }
        for (WhereFilter f : filters) {
            where.add(".and($T.$L($L))",
                ClassName.bestGuess(f.className()), f.methodName(),
                ArgCallEmitter.buildCallArgs(ctx, f.callParams(), f.className(), firstAlias));
        }
        sel.add(".where($L)\n", where.build());
        sel.add(".fetch();\n");
        sel.unindent();
        body.add(sel.build());

        body.addStatement("return scatterSingleByIdx(flat, keys.size())");

        return RowsMethodSkeleton.build(
            rowsMethodName,
            listOfRecord,
            keysListType,
            ctx.graphitronContextCall(),
            permitFactory.apply(body.build()));
    }

    // -----------------------------------------------------------------------
    // Connection-cardinality list method — window-function envelope
    // -----------------------------------------------------------------------

    /**
     * List-with-per-parent-pagination sibling of {@link #buildListMethod}. Emits a
     * {@code ROW_NUMBER() OVER (PARTITION BY parentInput.idx ORDER BY <effectiveOrderBy>)}
     * envelope and returns a {@code List<ConnectionResult>} indexed 1:1 with the DataLoader's
     * keys, where each element carries that parent's over-fetched slice plus the shared
     * pagination state.
     *
     * <p>Batching invariant: GraphQL field arguments are selection-set literals, resolved
     * once per field selection, so every sibling resolver of the same selection sees the
     * same {@code first/last/after/before} values. Aliases produce distinct selections and
     * therefore distinct DataLoader paths, so two aliased uses of the same field with
     * different args go to different batches. That is why the pagination dance runs once
     * per rows invocation from the first context in
     * {@code BatchLoaderEnvironment.getKeyContextsList()} and the resulting
     * {@code PageRequest} is wired into every per-parent slice.
     *
     * <p>Cursor semantics: because one cursor is shared across the batch, a client that
     * pages "the next N items after cursor C" sees each parent independently filtered by
     * {@code (orderBy cols) > C}. With a globally meaningful ordering (primary key, or any
     * totally-ordered column set), this reads as "paginate these parents' connections in
     * lockstep through the same cursor space" — which is the semantics graphql-java's
     * field-args model naturally produces.
     */
    private static MethodSpec buildConnectionMethod(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            String rowsMethodName,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<JoinStep> joinPath,
            List<WhereFilter> filters,
            SourceKey sourceKey,
            no.sikt.graphitron.rewrite.model.OrderBySpec orderBy,
            no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn,
            ParentCorrelation parentCorrelation,
            String outputPackage,
            java.util.function.Function<CodeBlock, RowsMethodBody> permitFactory) {

        ClassName typeClass = ClassName.get(
            outputPackage + ".types",
            returnType.returnTypeName());
        ClassName connectionResultClass = ClassName.get(
            outputPackage + ".util", "ConnectionResult");
        ClassName connectionHelperClass = ClassName.get(
            outputPackage + ".util", "ConnectionHelper");
        ClassName pageRequestClass = ClassName.get(
            outputPackage + ".util", "ConnectionHelper", "PageRequest");
        ClassName sortFieldClass = ClassName.get("org.jooq", "SortField");
        TypeName sortFieldWildcard = ParameterizedTypeName.get(
            sortFieldClass, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfSortField = ParameterizedTypeName.get(LIST, sortFieldWildcard);
        TypeName listOfConnectionResult = ParameterizedTypeName.get(LIST, connectionResultClass);

        var body = CodeBlock.builder();
        PreludeBindings p = emitParentInputAndFkChain(ctx,
            body, fieldName, sourceKey, returnType, joinPath, parentCorrelation);
        List<JoinStep> path = joinPath;
        List<String> aliases = p.aliases();
        String terminalAlias = p.terminalAlias();
        String firstAlias = p.firstAlias();
        String joinOnAlias = p.joinOnAlias();
        List<ColumnRef> joinOnCols = p.joinOnCols();
        List<ColumnRef> joinOnParentCols = p.joinOnParentCols();
        TypeName keyElement = p.keyElement();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);

        // Extract pagination args up front. Invariant: same values across every key in the batch,
        // because the DataLoader name is path-scoped and graphql-java resolves args per-field-path.
        body.addStatement("$T first = env.getArgument($S)", Integer.class, "first");
        body.addStatement("$T last = env.getArgument($S)", Integer.class, "last");
        body.addStatement("$T after = env.getArgument($S)", String.class, "after");
        body.addStatement("$T before = env.getArgument($S)", String.class, "before");

        // OrderBy derivation. Fixed inlined, Argument delegated to the <fieldName>OrderBy helper
        // emitted by TypeFetcherGenerator (same helper shape as root connections). None is rejected
        // upstream at validator time so empty-tuple cursors can't happen.
        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST, wildField);
        ClassName orderByResultClass = ClassName.get(
            outputPackage + ".util", "OrderByResult");
        switch (orderBy) {
            case no.sikt.graphitron.rewrite.model.OrderBySpec.Fixed fixed when !fixed.columns().isEmpty() -> {
                var sortParts = CodeBlock.builder();
                var colParts = CodeBlock.builder();
                for (int i = 0; i < fixed.columns().size(); i++) {
                    if (i > 0) { sortParts.add(", "); colParts.add(", "); }
                    var col = fixed.columns().get(i);
                    sortParts.add("$L.$L.$L()", terminalAlias, col.column().javaName(), col.direction().jooqMethodName());
                    colParts.add("$L.$L", terminalAlias, col.column().javaName());
                }
                body.addStatement("$T orderBy = $T.of($L)", listOfSortField, LIST, sortParts.build());
                body.addStatement("$T extraFields = $T.of($L)", listOfField, LIST, colParts.build());
            }
            case no.sikt.graphitron.rewrite.model.OrderBySpec.Argument arg -> {
                body.addStatement("$T ordering = $LOrderBy(env, $L)",
                    orderByResultClass, fieldName, terminalAlias);
                body.addStatement("$T orderBy = ordering.sortFields()", listOfSortField);
                body.addStatement("$T extraFields = ordering.columns()", listOfField);
            }
            default -> throw new IllegalStateException(
                "SplitTableField+Connection with empty/None orderBy reached emitter for field '" + fieldName
                + "'; validator should have rejected.");
        }

        body.addStatement(
            "$T page = $T.pageRequest(first, last, after, before, $L, orderBy, extraFields, "
                + "$T.$$fields(env.getSelectionSet(), $L, env))",
            pageRequestClass, connectionHelperClass, conn.defaultPageSize(), typeClass, terminalAlias);

        // selectFields = page.selectFields() + idx.as("__idx__") + rowNumber.as("__rn__").
        // rowNumber partitions on the idx column (so each parent sees its own 1..N ordinal) and
        // orders by effectiveOrderBy (reversed in the backward-pagination case).
        body.addStatement("$T<$T> selectFields = new $T<>(page.selectFields())",
            ClassName.get("java.util", "ArrayList"), wildField, ClassName.get("java.util", "ArrayList"));
        body.addStatement("$T<Integer> idxField = parentInput.field(0, $T.class)",
            FIELD, Integer.class);
        body.addStatement("selectFields.add(idxField.as($S))", IDX_COLUMN);
        body.addStatement("selectFields.add($T.rowNumber().over($T.partitionBy(idxField).orderBy(page.effectiveOrderBy())).as($S))",
            DSL, DSL, RN_COLUMN);

        // Inner windowed SELECT — attaches .orderBy()/.seek() for cursor-driven filtering; the
        // OS-level seek predicate falls in as WHERE, filtering BEFORE ROW_NUMBER() is computed.
        var inner = CodeBlock.builder();
        inner.add("$T<?> ranked = dsl\n", TABLE);
        inner.indent();
        inner.add(".select(selectFields)\n");
        inner.add(".from($L)\n", terminalAlias);
        // Bridging hops dispatch on step type (FkJoin vs ConditionJoin).
        for (int i = path.size() - 1; i >= 1; i--) {
            JoinStep bridging = path.get(i);
            String prevAlias = aliases.get(i - 1);
            switch (bridging) {
                case JoinStep.FkJoin fk -> inner.add(".join($L).onKey($T.$L)\n",
                    prevAlias, fk.fk().keysClass(), fk.fk().constantName());
                case JoinStep.ConditionJoin cj -> inner.add(".join($L).on($L)\n",
                    prevAlias, JoinPathEmitter.emitTwoArgMethodCall(cj.condition(), aliases.get(i), prevAlias));
                case JoinStep.LiftedHop ignored -> throw new IllegalStateException(
                    "LiftedHop should not appear at bridging position in a connection rows-method");
            }
        }
        // OnConditionJoin: bring parentAlias into scope via the step-0 condition method, then
        // pair parentInput on parent-PK columns. OnFkSlots pairs parentInput directly with
        // firstAlias on the first hop's slot columns.
        if (parentCorrelation instanceof ParentCorrelation.OnConditionJoin cj) {
            inner.add(".join(parentAlias).on($L)\n",
                JoinPathEmitter.emitTwoArgMethodCall(cj.firstHop().condition(), "parentAlias", firstAlias));
        }
        var onCond = CodeBlock.builder();
        for (int i = 0; i < joinOnCols.size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef parentCol = joinOnParentCols.get(i);
            ClassName parentColType = ClassName.bestGuess(parentCol.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($S, $T.class))",
                joinOnAlias,
                joinOnCols.get(i).javaName(),
                parentCol.sqlName(), parentColType);
            if (i > 0) onCond.add(")");
        }
        inner.add(".join(parentInput).on($L)\n", onCond.build());

        // WHERE: per-hop filters + field-level filters. Seek predicate added by .seek() below.
        var where = CodeBlock.builder();
        where.add("$T.noCondition()", DSL);
        for (int i = 0; i < path.size(); i++) {
            JoinStep.FkJoin hop = (JoinStep.FkJoin) path.get(i);
            if (hop.whereFilter() != null) {
                String srcAlias = i == 0 ? firstAlias : aliases.get(i - 1);
                String tgtAlias = aliases.get(i);
                where.add(".and($L)",
                    JoinPathEmitter.emitTwoArgMethodCall(hop.whereFilter(), srcAlias, tgtAlias));
            }
        }
        for (WhereFilter f : filters) {
            where.add(".and($T.$L($L))",
                ClassName.bestGuess(f.className()), f.methodName(),
                ArgCallEmitter.buildCallArgs(ctx, f.callParams(), f.className(), terminalAlias));
        }
        inner.add(".where($L)\n", where.build());
        inner.add(".orderBy(page.effectiveOrderBy())\n");
        inner.add(".seek(page.seekFields())\n");
        inner.add(".asTable($S);\n", "ranked");
        inner.unindent();
        body.add(inner.build());

        // Outer: filter by row_number. DSL.select() with no args projects every field from the
        // subquery — including __idx__ and the original selection; the __rn__ column is referenced
        // only in the WHERE clause and gets pulled into the projection too, which the scatter
        // ignores (it reads by name, not by index).
        var outer = CodeBlock.builder();
        outer.add("$T<$T> flat = dsl\n",
            ClassName.get("org.jooq", "Result"), RECORD);
        outer.indent();
        outer.add(".select()\n");
        outer.add(".from(ranked)\n");
        outer.add(".where(ranked.field($S, $T.class).le($T.val(page.limit())))\n",
            RN_COLUMN, Integer.class, DSL);
        outer.add(".fetch();\n");
        outer.unindent();
        body.add(outer.build());

        body.addStatement("return scatterConnectionByIdx(flat, keys.size(), page)");

        return RowsMethodSkeleton.build(
            rowsMethodName,
            listOfConnectionResult,
            keysListType,
            ctx.graphitronContextCall(),
            permitFactory.apply(body.build()));
    }

    // -----------------------------------------------------------------------
    // Scatter helper — emitted once per fetcher class that has any Split* field.
    // -----------------------------------------------------------------------

    /**
     * Builds the private static {@code emptyScatter(int keyCount)} helper returning a
     * pre-populated list of empty sublists. Used by the SplitLookupTableField rows method's
     * empty-lookup-input short-circuit (when {@code @lookupKey} args are null/empty, every
     * parent gets an empty result without touching the database).
     */
    public static MethodSpec buildEmptyScatterHelper() {
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        return MethodSpec.methodBuilder("emptyScatter")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfListOfRecord)
            .addParameter(int.class, "keyCount")
            .addCode(CodeBlock.builder()
                .addStatement("$T out = new $T<>(keyCount)", listOfListOfRecord, ARRAY_LIST)
                .beginControlFlow("for (int i = 0; i < keyCount; i++)")
                .addStatement("out.add(new $T<>())", ARRAY_LIST)
                .endControlFlow()
                .addStatement("return out")
                .build())
            .build();
    }

    /**
     * Single-cardinality sibling of {@link #buildScatterByIdxHelper}. Builds the private static
     * {@code scatterSingleByIdx(Result<Record>, int)} helper that turns a flat result into a
     * {@code List<Record>} indexed 1:1 with the DataLoader's key list (null where no match).
     *
     * <p>Invariant enforced at runtime: at most one terminal row per idx. The
     * {@code terminal.pk = parentInput.fk_value} JOIN cannot yield more than one row per key,
     * so two rows at the same idx indicates a misconfiguration; we surface it as an
     * {@link IllegalStateException} rather than silently discarding rows.
     */
    public static MethodSpec buildScatterSingleByIdxHelper() {
        TypeName resultRecord = ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), RECORD);
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        ClassName arrays = ClassName.get("java.util", "Arrays");
        return MethodSpec.methodBuilder("scatterSingleByIdx")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(resultRecord, "flat")
            .addParameter(int.class, "keyCount")
            .addCode(CodeBlock.builder()
                .addStatement("$T[] out = new $T[keyCount]", RECORD, RECORD)
                .beginControlFlow("for ($T r : flat)", RECORD)
                .addStatement("int idx = r.get($S, $T.class)", IDX_COLUMN, Integer.class)
                .beginControlFlow("if (out[idx] != null)")
                .addStatement("throw new $T($S + idx + $S)",
                    IllegalStateException.class,
                    "scatterSingleByIdx: two rows at idx ",
                    " — single-cardinality @splitQuery contract requires ≤1 terminal row per key")
                .endControlFlow()
                .addStatement("out[idx] = r")
                .endControlFlow()
                .addStatement("return $T.asList(out)", arrays)
                .build())
            .build();
    }

    /**
     * Builds the private static {@code scatterByIdx(Result<Record>, int)} helper that turns a
     * flat result into the per-key lists the DataLoader expects. Emitted once per fetcher class.
     */
    public static MethodSpec buildScatterByIdxHelper() {
        TypeName resultRecord = ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), RECORD);
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        return MethodSpec.methodBuilder("scatterByIdx")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfListOfRecord)
            .addParameter(resultRecord, "flat")
            .addParameter(int.class, "keyCount")
            .addCode(CodeBlock.builder()
                .addStatement("$T out = new $T<>(keyCount)", listOfListOfRecord, ARRAY_LIST)
                .beginControlFlow("for (int i = 0; i < keyCount; i++)")
                .addStatement("out.add(new $T<>())", ARRAY_LIST)
                .endControlFlow()
                .beginControlFlow("for ($T r : flat)", RECORD)
                .addStatement("int idx = r.get($S, $T.class)", IDX_COLUMN, Integer.class)
                .addStatement("out.get(idx).add(r)")
                .endControlFlow()
                .addStatement("return out")
                .build())
            .build();
    }

    /**
     * Connection-cardinality sibling of {@link #buildScatterByIdxHelper}. Buckets the flat
     * windowed result by {@code __idx__}, wrapping each per-parent sublist in a
     * {@link no.sikt.graphitron.rewrite.ConnectionResult} that shares the batch's
     * {@code PageRequest} (page size, cursors, backward flag, orderByColumns). Emitted once
     * per fetcher class that has any connection-returning Split* field.
     *
     * <p>The PageRequest's {@code extraFields()} are the order-by columns (cursor-encoding
     * seed); the shared {@code PageRequest} is what lets every per-parent
     * {@code ConnectionResult} answer {@code hasNextPage()} correctly: the over-fetch-by-1
     * lives per-partition in the windowed CTE, so each parent's bucket is 0..(pageSize+1).
     */
    public static MethodSpec buildScatterConnectionByIdxHelper(String outputPackage) {
        TypeName resultRecord = ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), RECORD);
        ClassName connectionResultClass = ClassName.get(
            outputPackage + ".util", "ConnectionResult");
        ClassName pageRequestClass = ClassName.get(
            outputPackage + ".util", "ConnectionHelper", "PageRequest");
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        TypeName listOfConnectionResult = ParameterizedTypeName.get(LIST, connectionResultClass);
        return MethodSpec.methodBuilder("scatterConnectionByIdx")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfConnectionResult)
            .addParameter(resultRecord, "flat")
            .addParameter(int.class, "keyCount")
            .addParameter(pageRequestClass, "page")
            .addCode(CodeBlock.builder()
                .addStatement("$T buckets = new $T<>(keyCount)", listOfListOfRecord, ARRAY_LIST)
                .beginControlFlow("for (int i = 0; i < keyCount; i++)")
                .addStatement("buckets.add(new $T<>())", ARRAY_LIST)
                .endControlFlow()
                .beginControlFlow("for ($T r : flat)", RECORD)
                .addStatement("int idx = r.get($S, $T.class)", IDX_COLUMN, Integer.class)
                .addStatement("buckets.get(idx).add(r)")
                .endControlFlow()
                .addStatement("$T out = new $T<>(keyCount)", listOfConnectionResult, ARRAY_LIST)
                .beginControlFlow("for (int i = 0; i < keyCount; i++)")
                .addStatement("out.add(new $T(buckets.get(i), page))", connectionResultClass)
                .endControlFlow()
                .addStatement("return out")
                .build())
            .build();
    }
}
