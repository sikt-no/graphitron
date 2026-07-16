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
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.RowsMethodBody;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.generators.util.ValuesJoinRowBuilder;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * cardinality and condition-join paths emit runtime-throwing stubs with reasons
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
     * the FK hop's source-side columns (FK-holder side, terminal for list cardinality); on
     * the lifter path, the {@code OnLiftedSlots} correlation's
     * {@link no.sikt.graphitron.rewrite.model.HasSlots#targetSideColumns()} (the DataLoader key
     * tuple IS the target-column tuple by construction); on the
     * @sourceRow + @reference path, the FK hop's source-side columns again. The prelude resolves
     * this fork once via a sealed switch and exports the ready list; the consumer iterates without
     * re-switching.
     *
     * <p>{@code joinOnParentCols} is parallel to {@code joinOnCols}: index {@code i} is the parent
     * column that {@code joinOnCols.get(i)} references. On the catalog-FK path that is
     * the hop's target-side columns (the FK's parent-side referenced columns, paired
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
        // For ParentCorrelation.OnParentJoin: parentAlias (the @table-bound parent declared
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
     * Builds the {@code DSL.row(...)} cell list for one parent-input {@code VALUES} row:
     * {@code DSL.inline(i)} followed by one
     * {@code DSL.val(<scalar>, Tables.<OWNER>.<COL>.getDataType())} per key column, delegated
     * to {@link ValuesJoinRowBuilder#cellsCode} (the single VALUES-cell authority) so jOOQ
     * binds each cell through the column's registered {@code Converter} at the DB type (an
     * untyped bind renders converter-backed / domain-typed keys at the wrong SQL type and
     * the correlation JOIN has no matching operator).
     *
     * <p>The scalar extraction forks on {@link SourceKey.Wrap} — the axis that decides whether
     * the key row exposes value accessors: {@code RecordN} keys read {@code k.valueN()}
     * directly; {@code RowN} keys have no value accessors (jOOQ {@code Row} is a schema
     * construct), so the value is pulled out of the bind {@code Param} the key was constructed
     * from via the per-fetcher-class {@code parentKeyCellValue} helper (see
     * {@link #buildParentKeyCellValueHelper()}). {@link SourceKey.Wrap.TableRecord} keys never
     * reach the parent-input seam (their variants route through service-lift or produced-record
     * reads, not this prelude).
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
     * The typed {@code parentInput.field(...)} lookup for one JOIN-predicate slot:
     * {@code parentInput.field("<sqlName>", Tables.<OWNER>.<COL>.getDataType())}. Paired with
     * {@link #parentKeyCells} so the looked-up {@code Field}'s type metadata matches the cell
     * binds (the derived table's column SQL types come from the cells; the lookup's
     * {@code DataType} keeps the predicate's Java-side view faithful and symmetric).
     */
    private static CodeBlock parentInputFieldLookup(String valuesLocal, ColumnRef parentCol, TableRef ownerTable) {
        return CodeBlock.of("$L.field($S, $T.$L.$L.getDataType())",
            valuesLocal, parentCol.sqlName(),
            ownerTable.constantsClass(), ownerTable.javaFieldName(), parentCol.javaName());
    }

    /**
     * Builds the private static {@code parentKeyCellValue(Field<?>)} helper that extracts the
     * scalar value out of a {@code RowN}-shaped DataLoader key's cell. {@code RowN} keys are
     * constructed via {@code DSL.row(value, ...)}, which wraps each scalar in a bind
     * {@code Param}; jOOQ's {@code Row} exposes cells only as {@code Field}s, so the value is
     * recovered through the {@code Param} narrowing. For generator-built keys the cast always
     * holds; for {@code @sourceRow} lifter keys it is a documented contract — a lifter that
     * builds its {@code RowN} from column references (not scalar values) gets this diagnostic
     * instead of a silently mistyped bind. Emitted once per fetcher class that has any
     * Row-keyed parent-input rows method (gate in {@code TypeFetcherGenerator}).
     */
    public static MethodSpec buildParentKeyCellValueHelper() {
        TypeName fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        ClassName param = ClassName.get("org.jooq", "Param");
        return MethodSpec.methodBuilder("parentKeyCellValue")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(fieldWildcard, "f")
            .addCode(CodeBlock.builder()
                .beginControlFlow("if (f instanceof $T<?> p)", param)
                .addStatement("return p.getValue()")
                .endControlFlow()
                .addStatement("throw new $T($S + f)",
                    IllegalStateException.class,
                    "DataLoader key cell must be a bind value (DSL.row over scalar values); got ")
                .build())
            .build();
    }

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
        // lifted correlation / FK-derived Hop chain). All four produce RowN<...> of the same Java types as the
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
            parentRowTypeArgs[i + 1] = pkCols.get(i).columnType();
        }
        TypeName parentRowType = ParameterizedTypeName.get(rowClass(parentRowArity), parentRowTypeArgs);
        TypeName parentRecordType = ParameterizedTypeName.get(recordClass(parentRowArity), parentRowTypeArgs);
        TypeName parentInputTableType = ParameterizedTypeName.get(TABLE, parentRecordType);

        // Classifier contract: joinPath is non-empty for an @reference-correlated split child
        // (the parent-correlation JOIN needs at least the first hop's slots), and empty for the
        // pre-keyed lifted shape (ParentCorrelation.OnLiftedSlots), whose single target
        // alias is synthesized from the correlation's target table exactly as the retired
        // single-LiftedHop path derived it. An empty joinPath with a null correlation is the
        // standalone-lookup shape, which the classifier does not route here; guard it explicitly
        // so a classifier regression fails loudly with the cause rather than as an opaque
        // "Index -1 out of bounds" on the empty alias list below.
        if (joinPath.isEmpty() && !(parentCorrelation instanceof ParentCorrelation.OnLiftedSlots)) {
            throw new IllegalStateException(
                "SplitRowsMethodEmitter reached a standalone (empty-joinPath) shape for field '"
                + fieldName + "'; a DataLoader-backed split child requires a parent-correlation join "
                + "path. This is a classifier invariant violation — standalone references are emitted "
                + "inline, not as split rows methods.");
        }
        List<String> aliases = parentCorrelation instanceof ParentCorrelation.OnLiftedSlots lifted
            ? List.of(JoinPathEmitter.liftedAlias(lifted.targetTable()))
            : JoinPathEmitter.generateAliases(joinPath, terminalTable);
        String terminalAlias = aliases.get(aliases.size() - 1);
        String firstAlias = aliases.get(0);
        // Classifier contract: joinPath is non-empty. The first step is either a filter-less FK-style
        // hop (an FK-derived Hop with pairable slots), a pre-keyed lifted correlation, a parent-anchored hop
        // (a condition-join OR any hop-0 filter), or a lateral routine head. The sealed switch
        // on parentCorrelation routes them uniformly: OnFkSlots reads the slot pairs as before;
        // OnParentJoin declares the parent-alias table local and routes parentInput's JOIN through the
        // parent's own PK columns (the parent-PK grain the arm dictates).
        String joinOnAlias;
        List<ColumnRef> joinOnCols;
        List<ColumnRef> joinOnParentCols;
        switch (parentCorrelation) {
            case ParentCorrelation.OnFkSlots fk -> {
                var firstSlots = fk.slots();
                joinOnAlias = firstAlias;
                joinOnCols = firstSlots.targetSideColumns();
                joinOnParentCols = firstSlots.sourceSideColumns();
            }
            case ParentCorrelation.OnLiftedSlots lifted -> {
                // Pre-keyed shape: source and target sides are the same column tuple
                // (PK self-identity for the re-fetch, the lifter/accessor key otherwise).
                joinOnAlias = firstAlias;
                joinOnCols = lifted.columns();
                joinOnParentCols = lifted.columns();
            }
            case ParentCorrelation.OnParentJoin pj -> {
                // ParentInput joins on the parent table's own PK columns: the predicate is
                // parentAlias.<pkCol> = parentInput.field("<pkCol.sqlName>"). The DataLoader
                // key tuple IS the parent-PK tuple (parentKeyColumns), so the cols on both sides
                // of the predicate are the same ColumnRef set — only the alias to put them on
                // differs. Hop 0 then attaches off parentAlias (its On dispatched in
                // emitFromBridgeAndParentJoin), so a hop-0 filter has a real parent alias to bind.
                joinOnAlias = "parentAlias";
                joinOnCols = pj.parentKeyColumns();
                joinOnParentCols = pj.parentKeyColumns();
            }
            case ParentCorrelation.OnLateralArgs ignored -> {
                // A lateral routine hop at step 0 correlates through its call arguments —
                // the SourceColumn bindings read parentInput fields directly (they ARE the
                // DataLoader key), so the CROSS JOIN LATERAL carries no ON predicate at all.
                joinOnAlias = firstAlias;
                joinOnCols = List.of();
                joinOnParentCols = List.of();
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
        body.addStatement("parentRows[i] = $T.row($L)", DSL,
            parentKeyCells(sourceKey, pkCols, parentCorrelation.parentKeyOwnerTable()));
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
        // HasTargetTable so every step surfaces its pre-resolved targetTable without an
        // arm-specific cast (condition-join hops now carry a resolved TableRef; see
        // BuildContext.resolveConditionJoinTarget).
        // The lifted shape has no hops; declare its single synthesized target alias directly
        // (byte-identical to the retired single-LiftedHop declaration: the target is always a
        // catalog table).
        if (parentCorrelation instanceof ParentCorrelation.OnLiftedSlots lifted) {
            TableRef liftedTarget = lifted.targetTable();
            body.addStatement("$T $L = $T.$L.as($S)",
                liftedTarget.tableClass(), firstAlias,
                liftedTarget.constantsClass(), liftedTarget.javaFieldName(),
                fieldName + "_" + firstAlias);
        }
        for (int i = 0; i < joinPath.size(); i++) {
            JoinStep.HasTargetTable step = (JoinStep.HasTargetTable) joinPath.get(i);
            ClassName jooqTableClass = step.targetTable().tableClass();
            // Materialization routes through the shared TableExpr switch. A routine hop
            // heading the chain reads its correlated columns off parentInput (the implicit head
            // is not materialised in the batch query — its bound columns ARE the DataLoader
            // key); a mid-chain routine hop reads the preceding hop's alias like the inline form.
            PreviousNodeRef previousNode = i > 0
                ? new PreviousNodeRef.TypedAlias(aliases.get(i - 1))
                : parentCorrelation instanceof ParentCorrelation.OnLateralArgs
                    ? new PreviousNodeRef.ParentInputField("parentInput", parentCorrelation.parentKeyOwnerTable())
                    : new PreviousNodeRef.TypedAlias("parentAlias");
            // ^ The "parentAlias" placeholder is only ever read for a lateral routine hop (to
            // reference the previous node inside the call args); a Catalog target ignores
            // previousNode. Only the OnParentJoin arm actually declares a parentAlias local
            // (below) — OnFkSlots materialises hop 0 off parentInput's slot columns and needs none.
            body.addStatement("$T $L = $L.as($S)",
                jooqTableClass, aliases.get(i),
                JoinPathEmitter.emitTableExpression(joinPath.get(i), previousNode,
                    new ArgumentValueSource.Env()),
                fieldName + "_" + aliases.get(i));
        }

        // OnParentJoin: declare the @table-bound parent alias the rows-method anchors on. Hop 0
        // attaches off it (via its On — an ordinary forward join for a filtered FK hop, or the
        // condition method for a condition-join hop), and parentInput pairs against it on the
        // parent-PK columns. OnFkSlots reuses firstAlias as the join-on alias and needs no extra
        // declaration.
        if (parentCorrelation instanceof ParentCorrelation.OnParentJoin pj) {
            TableRef parentTable = pj.parentTable();
            body.addStatement("$T parentAlias = $T.$L.as($S)",
                parentTable.tableClass(), parentTable.constantsClass(), parentTable.javaFieldName(),
                fieldName + "_parent");
        }

        return new PreludeBindings(aliases, terminalAlias, firstAlias, joinOnAlias, joinOnCols, joinOnParentCols, keyElement);
    }

    /**
     * Emits the flat join topology shared by all three cardinality siblings
     * ({@link #buildListMethod}, {@link #buildSingleMethod}, {@link #buildConnectionMethod}):
     * {@code .from(parentInput)}, the step-0 attach per correlation arm (including the optional
     * {@code OnParentJoin} parent JOIN), then the forward bridging-hop chain out to the terminal.
     * Appends to {@code sel} and stops before the WHERE clause (list inserts its lookup-input JOIN
     * there; connection appends its window tail), so each caller frames the projection and tail
     * itself.
     *
     * <p>This block was once the source of a bug: it lived as a byte-for-byte copy in
     * {@code buildListMethod} and {@code buildConnectionMethod} but never grew into
     * {@code buildSingleMethod}, which projected off {@code firstAlias} with no bridging loop and
     * was therefore single-hop only. Extracting it makes the topology uniform across the
     * cardinality fork; the genuine per-cardinality divergence (projection envelope, scatter call)
     * stays with each sibling.
     *
 * <p>The walk is start-first with {@code parentInput} as the FROM anchor: a lateral
     * routine hop's call arguments reference the previous node's alias — {@code parentInput}
     * itself at the chain head — and SQL LATERAL scoping only sees FROM entries to its left, so
     * the old terminal-back walk cannot host a lateral node. For the pure INNER-join chains every
     * other shape emits, anchor and direction are behaviour-equivalent (the execution tier pins
     * the multi-hop, single-cardinality, and connection shapes). The bridging loop dispatches on
     * step identity: FK hops use {@code .onKey(FK)} / the name-matched pair conjunction, condition
     * hops use {@code .on(method(prevAlias, alias))}, lateral routine hops use
     * {@code .crossJoin(DSL.lateral(alias))}. The pre-keyed lifted shape carries no hops at all
     * ({@code @reference}-composed paths are FK / condition chains; the lifted correlation is
     * hop-less), so the loop is a no-op for it and for any single-hop path.
     */
    private static void emitFromBridgeAndParentJoin(
            CodeBlock.Builder sel,
            List<JoinStep> path,
            List<String> aliases,
            String firstAlias,
            ParentCorrelation parentCorrelation,
            String joinOnAlias,
            List<ColumnRef> joinOnCols,
            List<ColumnRef> joinOnParentCols) {
        // The parentInput correlation predicate. For OnFkSlots, joinOnAlias is firstAlias and the
        // predicate pairs slot.targetSide()/slot.sourceSide(). For OnParentJoin, joinOnAlias is
        // parentAlias and the predicate pairs parent-PK on both sides. For OnLateralArgs the slot
        // lists are empty — correlation rides the lateral call's arguments. The parentInput field
        // is resolved by sqlName + the owner column's DataType rather than positional index,
        // sidestepping @node(keyColumns: [...]) vs FK column ordering mismatches and keeping
        // converter-backed columns' type metadata faithful.
        TableRef ownerTable = parentCorrelation.parentKeyOwnerTable();
        var onCond = CodeBlock.builder();
        for (int i = 0; i < joinOnCols.size(); i++) {
            if (i > 0) onCond.add(".and(");
            onCond.add("$L.$L.eq($L)",
                joinOnAlias,
                joinOnCols.get(i).javaName(),
                parentInputFieldLookup("parentInput", joinOnParentCols.get(i), ownerTable));
            if (i > 0) onCond.add(")");
        }
        sel.add(".from(parentInput)\n");
        // Step 0: attach the chain's first node to parentInput, per correlation arm.
        switch (parentCorrelation) {
            case ParentCorrelation.OnFkSlots ignored ->
                sel.add(".join($L).on($L)\n", firstAlias, onCond.build());
            case ParentCorrelation.OnLiftedSlots ignored ->
                sel.add(".join($L).on($L)\n", firstAlias, onCond.build());
            case ParentCorrelation.OnParentJoin pj -> {
                // parentAlias pairs with parentInput on the parent's PK, then hop 0 attaches
                // firstAlias (= aliases[0], the hop's target) off parentAlias. The attach dispatches
                // on the hop's own On (JoinStep's two-axis model): an ordinary forward join for an
                // FK hop carrying a hop-0 filter, or the two-arg condition method for a
                // condition-join hop. Either way firstAlias hangs off a real parent alias, so a
                // hop-0 filter (emitted in buildWhereCondition) can bind parentAlias as its source.
                sel.add(".join(parentAlias).on($L)\n", onCond.build());
                switch (pj.firstHop().on()) {
                    case On.ColumnPairs cp -> sel.add("$L\n",
                        JoinPathEmitter.emitForwardJoin(cp, "parentAlias", firstAlias));
                    case On.Predicate pred -> sel.add(".join($L).on($L)\n", firstAlias,
                        JoinPathEmitter.emitTwoArgMethodCall(pred.condition(), "parentAlias", firstAlias));
                    case On.Lateral ignored -> throw new IllegalStateException(
                        "ParentCorrelation.OnParentJoin cannot wrap a lateral hop; its compact "
                        + "constructor rejects On.Lateral (a routine node is OnLateralArgs)");
                }
            }
            case ParentCorrelation.OnLateralArgs ignored ->
                sel.add(".crossJoin($T.lateral($L))\n", DSL, firstAlias);
        }
        // Bridging hops: step 0 forward to the terminal. No-op when path.size() == 1.
        for (int i = 1; i < path.size(); i++) {
            JoinStep bridging = path.get(i);
            String prevAlias = aliases.get(i - 1);
            switch (bridging) {
                case JoinStep.Hop hop -> sel.add("$L\n",
                    JoinPathEmitter.emitForwardBridging(hop, prevAlias, aliases.get(i)));
            }
        }
    }

    /**
     * Builds the WHERE condition expression shared by the three cardinality siblings:
     * {@code DSL.noCondition()} AND-ed with each hop's {@code filter} (paired
     * {@code (srcAlias, tgtAlias)} per hop) AND the field-level {@code filters} (projected off
     * {@code terminalAlias}). Returns the condition CodeBlock; the caller wraps it in
     * {@code .where(...)} so the connection sibling can chain {@code .orderBy()/.seek()} after.
     *
     * <p>The per-hop filter is emitted as {@code method(srcAlias, tgtAlias)}, where {@code srcAlias}
     * is the hop's origin side: the previous hop's alias for hops 1..n, and for hop 0 the parent
     * alias declared by the {@link ParentCorrelation.OnParentJoin} arm. The classifier lands any
 * hop-0 {@code filter()} on that arm precisely so a parent alias exists here; under the
     * other arms a hop-0 filter is unreachable and guarded (the earlier code bound the hop-0
     * <em>target</em> alias as both parameters, so a filter's concretely-typed source parameter
     * failed javac and a wildcard-typed one produced silently self-referential SQL).
     *
     * <p>Only {@link JoinStep.Hop}s carry a per-hop filter; lifter hops are skipped by the
     * {@code instanceof} guard.
     */
    private static CodeBlock buildWhereCondition(
            CodeBlock.Builder body,
            TypeFetcherEmissionContext ctx,
            List<JoinStep> path,
            List<String> aliases,
            String terminalAlias,
            ParentCorrelation parentCorrelation,
            List<WhereFilter> filters,
            CompositeDecodeHelperRegistry registry) {
        // Declare an aliased FK-target table local per join hop for every FK-target @nodeId
        // override @condition among the filters, into the enclosing method's `body` (the WHERE is an
        // expression and cannot introduce locals itself). Each caller embeds the returned WHERE in a
        // select that is added to `body` after this call, so the aliases precede their use. Rows
        // methods recurse, so the SQL alias is runtime-prefixed onto the terminal alias's getName().
        Map<WhereFilter, List<String>> fkTargetAliases =
            FkTargetConditionEmitter.declareAliases(body, filters, terminalAlias, true);

        var where = CodeBlock.builder();
        where.add("$T.noCondition()", DSL);
        for (int i = 0; i < path.size(); i++) {
            if (!(path.get(i) instanceof JoinStep.Hop hop)) continue;
            if (hop.filter() != null) {
                String srcAlias;
                if (i == 0) {
                    // A hop-0 filter reads the parent row, so the classifier lands it on the
                    // parent-anchor arm (OnParentJoin) precisely so parentAlias is in scope to bind
                    // the filter's source parameter. Under OnFkSlots / OnLateralArgs parentInput
                    // carries no parent alias, so a hop-0 filter is classifier-unreachable — guard
                    // it loudly rather than silently bind the target alias twice as the earlier
                    // code did.
                    if (!(parentCorrelation instanceof ParentCorrelation.OnParentJoin)) {
                        throw new IllegalStateException(
                            "hop-0 filter reached buildWhereCondition under "
                            + parentCorrelation.getClass().getSimpleName() + "; the classifier lands "
                            + "any hop-0 filter on ParentCorrelation.OnParentJoin so a parent alias "
                            + "is in scope to bind the filter's source parameter (R450)");
                    }
                    srcAlias = "parentAlias";
                } else {
                    srcAlias = aliases.get(i - 1);
                }
                String tgtAlias = aliases.get(i);
                where.add("\n        .and($L)",
                    JoinPathEmitter.emitTwoArgMethodCall(hop.filter(), srcAlias, tgtAlias));
            }
        }
        for (WhereFilter f : filters) {
            where.add("\n        .and($L)",
                FkTargetConditionEmitter.emitTerm(ctx, f, terminalAlias, registry, null, fkTargetAliases, new ArgumentValueSource.Env()));
        }
        return where.build();
    }

    // -----------------------------------------------------------------------
    // BatchedTableField
    // -----------------------------------------------------------------------

    /**
     * Builds the rows-method for a {@link ChildField.BatchedTableField} (both source shapes;
     * the SQL bodies were already shared pre-merge). Sibling entry points
     * {@link #buildForSplitLookupTable} and {@link #buildForRecordLookupTable} cover the lookup
     * twins; each caller in {@code TypeFetcherGenerator} already has the concrete field type, so
     * no capability-typed dispatcher is needed at this seam.
     *
     * <p>Routing is on facts, not the source gate: {@code emitsSingleRecordPerKey} folds the
     * single-cardinality and loader.loadMany triggers onto {@code buildSingleMethod} (the flat
     * join + scatterSingleByIdx shape the loadMany dispatch requires — loader value type
     * {@code Record}, returning {@code List<Record>} 1:1 with keys, not
     * {@code List<List<Record>>}); the Connection arm is reachable only from the Table-sourced
     * arm (the leaf's ctor rejects Record + Connection). The capability fold matches
     * {@code TypeFetcherGenerator}'s scatterSingleByIdx helper-emission gate; both ask the same
     * uniform question.
     */
    static MethodSpec buildForBatchedTable(TypeFetcherEmissionContext ctx, ChildField.BatchedTableField btf, String outputPackage,
            CompositeDecodeHelperRegistry registry) {
        java.util.function.Function<CodeBlock, RowsMethodBody> permit = RowsMethodBody.SqlBatchedTable::new;
        // Declaration name resolved through the command-mint seam (R314): the Record-sourced arm
        // commits a reentry MethodCommand, the Table-sourced arm passes through uncommitted.
        String rowsName = ctx.rowsDeclarationName(btf);
        if (btf.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
            return buildConnectionMethod(
                ctx, btf.name(), rowsName, btf.returnType(),
                btf.joinPath(), btf.filters(), btf.sourceKey(), btf.orderBy(), conn,
                btf.parentCorrelation(),
                outputPackage, permit, registry);
        }
        if (btf.emitsSingleRecordPerKey()) {
            return buildSingleMethod(
                ctx, btf.name(), rowsName, btf.returnType(),
                btf.joinPath(), btf.filters(), btf.sourceKey(),
                btf.parentCorrelation(),
                outputPackage, permit, registry);
        }
        return buildListMethod(
            ctx, btf.name(), rowsName, btf.returnType(),
            btf.joinPath(), btf.filters(), btf.sourceKey(),
            /* lookupMapping */ null,
            btf.parentCorrelation(),
            outputPackage, permit, registry);
    }

    // -----------------------------------------------------------------------
    // BatchedLookupTableField (C2)
    // -----------------------------------------------------------------------

    /**
     * See {@link #buildForBatchedTable} for the entry-point convention. Both source shapes: the
     * rows-method body was already identical for the pre-merge lookup twins — same
     * {@code SourceKey} (Wrap.Row) + {@code LookupMapping} shape, so {@code buildListMethod}
     * handles both. The parent-backing divergence (jOOQ-table-row vs backing-object key
     * extraction) lives above this seam, in {@code TypeFetcherGenerator}'s fetcher fork.
     */
    static MethodSpec buildForBatchedLookupTable(TypeFetcherEmissionContext ctx, ChildField.BatchedLookupTableField blf, String outputPackage,
            CompositeDecodeHelperRegistry registry) {
        return buildListMethod(
            ctx, blf.name(), ctx.rowsDeclarationName(blf), blf.returnType(),
            blf.joinPath(), blf.filters(), blf.sourceKey(),
            blf.lookupMapping(),
            blf.parentCorrelation(),
            outputPackage,
            RowsMethodBody.SqlBatchedLookupTable::new, registry);
    }

    // -----------------------------------------------------------------------
    // RecordTableMethodField
    // -----------------------------------------------------------------------

    /**
     * Rows-method for {@link ChildField.RecordTableMethodField}: the DTO-parent sibling of
     * the record-sourced {@link #buildForBatchedTable} arm. Identical parent-VALUES + flat-JOIN-on-FK shape; the only
     * difference is that the terminal table is materialised by calling the developer's static
     * {@code @tableMethod} (returning a generated jOOQ table) rather than referencing
     * {@code Tables.<X>} directly. Single-hop FK-derived {@link JoinStep.Hop} only, mirroring the
     * table-parent {@link ChildField.TableMethodField} emit's shipped shape;
     * multi-hop FK paths and empty joinPaths surface a runtime
     * {@link UnsupportedOperationException}. Condition-join first-hops are
     * caught at parse time by {@link no.sikt.graphitron.rewrite.FieldBuilder}'s
     * {@code buildParentCorrelation} call (the class-backed parent has no {@code @table} to
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
        // Declaration name through the command-mint seam (R314); RecordTableMethodField is
        // always Record-sourced, so this always commits a reentry MethodCommand — including
        // for the unsupported-path runtime stub below, which still declares the method.
        String rowsName = ctx.rowsDeclarationName(rtmf);

        List<JoinStep> path = rtmf.joinPath();
        // Condition-join first-hop on class-backed parent is caught upstream by FieldBuilder's
        // parentCorrelation synthesis (no parent @table to anchor); the predicate below only
        // covers the pre-existing limits on RecordTableMethodField (empty + multi-hop).
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
                rowsName,
                outerReturn,
                keysListType,
                ctx.graphitronContextCall(),
                new RowsMethodBody.SqlRecordTableMethod(stub));
        }

        var body = CodeBlock.builder();
        emitRecordTableMethodBody(ctx, body, rtmf, outputPackage, singleRecordPerKey);

        return RowsMethodSkeleton.build(
            rowsName,
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
            parentRowTypeArgs[i + 1] = pkCols.get(i).columnType();
        }
        TypeName parentRowType = ParameterizedTypeName.get(rowClass(parentRowArity), parentRowTypeArgs);
        TypeName parentRecordType = ParameterizedTypeName.get(recordClass(parentRowArity), parentRowTypeArgs);
        TypeName parentInputTableType = ParameterizedTypeName.get(TABLE, parentRecordType);

        body.add("@$T({$S, $S})\n", ClassName.get("java.lang", "SuppressWarnings"), "unchecked", "rawtypes");
        body.addStatement("$T[] parentRows = ($T[]) new $T[keys.size()]",
            parentRowType, parentRowType, rowClass(parentRowArity));
        body.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        body.addStatement("$T k = keys.get(i)", keyElement);
        body.addStatement("parentRows[i] = $T.row($L)", DSL,
            parentKeyCells(sourceKey, pkCols, rtmf.parentCorrelation().parentKeyOwnerTable()));
        body.endControlFlow();

        var parentInputAlias = CodeBlock.builder();
        parentInputAlias.add("$S, $S", "parentInput", "idx");
        for (var col : pkCols) {
            parentInputAlias.add(", $S", col.sqlName());
        }
        body.addStatement("$T parentInput = $T.values(parentRows).as($L)",
            parentInputTableType, DSL, parentInputAlias.build());

        // Terminal table: invoke the developer's static @tableMethod. Aliased so the parent-input
        // JOIN can address its columns via the local. The leading-table parameter was retired;
        // ArgCallEmitter is called with null for the table expression.
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

        // JOIN parentInput on FK columns. The single FK hop's slots pair source (parent) and
        // target (developer table) columns; the parent-side column lookup goes by sqlName + the
        // owner column's DataType (sidestepping potential @node ordering mismatches and keeping
        // converter-backed columns' type metadata faithful).
        var firstHop = (On.ColumnPairs) ((JoinStep.Hop) rtmf.joinPath().get(0)).on();
        TableRef ownerTable = rtmf.parentCorrelation().parentKeyOwnerTable();
        var onCond = CodeBlock.builder();
        int slotIdx = 0;
        for (var slot : firstHop.slots()) {
            if (slotIdx > 0) onCond.add(".and(");
            onCond.add("$L.$L.eq($L)",
                terminalAlias,
                slot.targetSide().javaName(),
                parentInputFieldLookup("parentInput", slot.sourceSide(), ownerTable));
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


    /**
     * Shared body emitter for list-cardinality Split* rows methods. For
     * {@link ChildField.BatchedTableField} pass {@code lookupMapping = null}; for
     * {@link ChildField.BatchedLookupTableField} pass its mapping and the emitter adds a second
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
            java.util.function.Function<CodeBlock, RowsMethodBody> permitFactory,
            CompositeDecodeHelperRegistry registry) {
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
                lookupTypeArgs[i + 1] = lookupCols.get(i).columnType();
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

        // Flat SELECT: FROM parentInput, step-0 attach per correlation arm (slot columns eq
        // parentInput.field(sqlName), or the OnParentJoin parent-PK pairing), then the forward
        // bridging hops out to the terminal. Shared with the single and connection siblings via
        // emitFromBridgeAndParentJoin.
        var sel = CodeBlock.builder();
        sel.add("$T<$T> flat = dsl\n", ClassName.get("org.jooq", "Result"), RECORD);
        sel.indent();
        sel.add(".select(selectFields)\n");
        emitFromBridgeAndParentJoin(sel, path, aliases, firstAlias,
            parentCorrelation, joinOnAlias, joinOnCols, joinOnParentCols);

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
                TypeName colType = col.columnType();
                lookupOnCond.add("$L.$L.eq(lookupInput.field($L, $T.class))",
                    terminalAlias, col.javaName(),
                    i + 1, colType);
                if (i > 0) lookupOnCond.add(")");
            }
            sel.add(".join(lookupInput).on($L)\n", lookupOnCond.build());
        }

        // WHERE: per-hop whereFilters + field-level filters, shared with the single and connection
        // siblings via buildWhereCondition.
        sel.add(".where($L)\n",
            buildWhereCondition(body, ctx, path, aliases, terminalAlias, parentCorrelation, filters, registry));
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
     * Single-cardinality sibling of {@link #buildListMethod} for {@link ChildField.BatchedTableField}.
     * Parent-holds-FK at step 0 ({@link ChildField.BatchedTableField}'s {@code sourceKey} carries the
     * parent's FK columns per {@code FieldBuilder.deriveSplitQuerySource}). Emits a flat
     * {@code parentInput JOIN <step-0 attach> <bridging hops out to the terminal>} SELECT that
     * returns {@code List<Record>} indexed 1:1 with {@code keys} (nulls where no match).
     *
     * <p>Shares its join topology and WHERE clause with {@link #buildListMethod} and
     * {@link #buildConnectionMethod} via {@link #emitFromBridgeAndParentJoin} and
     * {@link #buildWhereCondition}; the only per-cardinality divergence is the {@code List<Record>}
     * return shape and the {@code scatterSingleByIdx} (1:1, null where no match) call. The shared
     * topology anchors on {@code parentInput} and walks the bridging hops start-first out to
 * {@code terminalAlias}, which carries the projection, so a multi-hop single-cardinality
     * {@code @splitQuery} (e.g. {@code customer -> store -> address}) resolves the terminal row per
     * key in one batched query. Single-hop paths collapse the bridging loop to a no-op, reproducing
     * the original single-hop shape.
     *
     * <p>The bridging hops emit inner joins, consistent with the list and connection siblings: a
     * to-one chain resolves to {@code null} when any hop is absent (the row drops, and
     * {@code scatterSingleByIdx} fills {@code null}). Distinguishing intermediate-null from
 * terminal-null (LEFT JOINs) is out of scope and would have to span all three siblings.
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
            java.util.function.Function<CodeBlock, RowsMethodBody> permitFactory,
            CompositeDecodeHelperRegistry registry) {
        ClassName typeClass = ClassName.get(
            outputPackage + ".types",
            returnType.returnTypeName());

        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);

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

        // Projection off terminalAlias (the end of the bridging chain), idx + the child selection.
        // Identical to buildListMethod modulo the scatter call; single-hop paths collapse the
        // bridging loop to a no-op so terminalAlias == firstAlias there.
        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST, wildField);
        body.addStatement("$T selectFields = new $T<>($T.$$fields(env.getSelectionSet(), $L, env))",
            listOfField, ARRAY_LIST, typeClass, terminalAlias);
        body.addStatement("selectFields.add(parentInput.field(0, $T.class).as($S))",
            Integer.class, IDX_COLUMN);

        var sel = CodeBlock.builder();
        sel.add("$T<$T> flat = dsl\n", ClassName.get("org.jooq", "Result"), RECORD);
        sel.indent();
        sel.add(".select(selectFields)\n");
        emitFromBridgeAndParentJoin(sel, path, aliases, firstAlias,
            parentCorrelation, joinOnAlias, joinOnCols, joinOnParentCols);
        sel.add(".where($L)\n",
            buildWhereCondition(body, ctx, path, aliases, terminalAlias, parentCorrelation, filters, registry));
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
            java.util.function.Function<CodeBlock, RowsMethodBody> permitFactory,
            CompositeDecodeHelperRegistry registry) {

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
                "BatchedTableField+Connection with empty/None orderBy reached emitter for field '" + fieldName
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

        // WHERE hoisted into a local so the windowed page query and the totalCount countSource
        // below share the exact same predicate. buildWhereCondition must be called exactly once:
        // it declares FK-target alias locals into the method body as a side effect
        // (FkTargetConditionEmitter.declareAliases), so a second call would emit duplicate
        // local declarations.
        body.addStatement("$T where = $L", ClassName.get("org.jooq", "Condition"),
            buildWhereCondition(body, ctx, path, aliases, terminalAlias, parentCorrelation, filters, registry));

        // Inner windowed SELECT — attaches .orderBy()/.seek() for cursor-driven filtering; the
        // OS-level seek predicate falls in as WHERE, filtering BEFORE ROW_NUMBER() is computed.
        var inner = CodeBlock.builder();
        inner.add("$T<?> ranked = dsl\n", TABLE);
        inner.indent();
        inner.add(".select(selectFields)\n");
        // Join topology + WHERE shared with the list and single siblings via
        // emitFromBridgeAndParentJoin / buildWhereCondition; the connection-specific window tail
        // (.orderBy/.seek/.asTable) is appended after.
        emitFromBridgeAndParentJoin(inner, path, aliases, firstAlias,
            parentCorrelation, joinOnAlias, joinOnCols, joinOnParentCols);
        inner.add(".where(where)\n");
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

        // Count-source derived table for per-parent totalCount: the same join topology and
        // hoisted WHERE as the page query, but no orderBy/seek so the count is cursor-independent.
        // Each per-parent ConnectionResult pairs it with an __idx__ = i condition; the generated
        // ConnectionHelper.totalCount then runs SELECT count(*) FROM countSource WHERE __idx__ = i
        // lazily on selection. Mirrors B4c-2's count semantics (MultiTablePolymorphicEmitter
        // .buildBatchedConnectionRowsMethod): zero count SQL when totalCount is unselected, N
        // count queries for a batch of N parents when selected.
        var count = CodeBlock.builder();
        count.add("$T<?> countSource = dsl\n", TABLE);
        count.indent();
        count.add(".select(idxField.as($S))\n", IDX_COLUMN);
        emitFromBridgeAndParentJoin(count, path, aliases, firstAlias,
            parentCorrelation, joinOnAlias, joinOnCols, joinOnParentCols);
        count.add(".where(where)\n");
        count.add(".asTable($S);\n", "countSource");
        count.unindent();
        body.add(count.build());

        body.addStatement("return scatterConnectionByIdx(flat, keys.size(), page, countSource)");

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
     *
     * <p>{@code countSource} is the shared cursor-independent count derived table emitted by
     * the rows method; each per-parent carrier binds it with an {@code __idx__ = i} condition
     * so the generated {@code ConnectionHelper.totalCount} can serve a per-parent count on
     * selection (same shape as the polymorphic batched path's shared {@code pages} table).
     */
    public static MethodSpec buildScatterConnectionByIdxHelper(String outputPackage) {
        TypeName resultRecord = ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), RECORD);
        ClassName connectionResultClass = ClassName.get(
            outputPackage + ".util", "ConnectionResult");
        ClassName pageRequestClass = ClassName.get(
            outputPackage + ".util", "ConnectionHelper", "PageRequest");
        TypeName tableWildcard = ParameterizedTypeName.get(TABLE, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        TypeName listOfConnectionResult = ParameterizedTypeName.get(LIST, connectionResultClass);
        return MethodSpec.methodBuilder("scatterConnectionByIdx")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfConnectionResult)
            .addParameter(resultRecord, "flat")
            .addParameter(int.class, "keyCount")
            .addParameter(pageRequestClass, "page")
            .addParameter(tableWildcard, "countSource")
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
                .addStatement("out.add(new $T(buckets.get(i), page, countSource,"
                        + " countSource.field($S, $T.class).eq($T.inline(i))))",
                    connectionResultClass, IDX_COLUMN, Integer.class, DSL)
                .endControlFlow()
                .addStatement("return out")
                .build())
            .build();
    }

    // -----------------------------------------------------------------------
    // ServiceTableField — lift-back projection
    // -----------------------------------------------------------------------

    /**
     * Rows-method for a {@link ChildField.ServiceTableField}: the condensed
     * {@code ServiceRecordField -> RecordTableField} (now the record-sourced {@code BatchedTableField} arm) shape. The developer's {@code @service}
     * method produces real {@code XRecord}s (the {@code serviceCall} expression, returning the
     * loader's {@code Map}/{@code List} container of {@code XRecord}); this method lifts those
     * back by extracting each returned record's primary key, re-projecting the bound table on
     * that key by identity through {@code Type.$fields(...)}, and re-wrapping the projected
     * {@code Record}s into the same container shape. Scalar sub-fields and {@code @reference}
     * multiset sub-fields both resolve off the projected record, where the verbatim service
     * return carried only stored columns.
     *
     * <p>The only difference from the record-sourced {@link #buildForBatchedTable} arm's element-PK re-projection is
     * timing: there the records are in hand at fetch time and the DataLoader key is the element
     * PK; here the records arrive from the service call inside the loader body, so the same
     * {@code rec.get(PK)} extraction runs rows-method-side and the DataLoader key stays the
     * parent key. The loader value type is therefore {@code Record} (the projected row carrying
     * the multiset columns), not the developer-returned {@code XRecord}.
     */
    public static MethodSpec buildServiceTableLift(
            TypeFetcherEmissionContext ctx,
            ChildField.ServiceTableField stf,
            CodeBlock serviceCall,
            String outputPackage) {

        ReturnTypeRef.TableBoundReturnType rt = stf.returnType();
        TableRef table = rt.table();
        SourceKey sourceKey = stf.sourceKey();
        boolean isMapped = stf.loaderRegistration().container()
            == no.sikt.graphitron.rewrite.model.LoaderRegistration.Container.MAPPED_SET;
        boolean isList = rt.wrapper().isList();
        TypeName keyElement = sourceKey.keyElementType();
        TypeName xRecord = table.recordClass();
        List<ColumnRef> pks = table.primaryKeyColumns();
        // VALUES row shape: (parentIdx, seq, pk…). idx drives the scatter back to the parent; seq
        // is the global flatten order so each parent's records keep the order the service returned
        // them in (the re-projection JOIN does not otherwise preserve it).
        int arity = pks.size() + 2;

        ClassName mapClass = ClassName.get("java.util", "Map");
        ClassName setClass = ClassName.get("java.util", "Set");
        ClassName linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
        ClassName suppress = ClassName.get("java.lang", "SuppressWarnings");

        TypeName serviceReturn = no.sikt.graphitron.rewrite.model.RowsMethodShape
            .outerRowsReturnType(xRecord, rt, keyElement, isMapped);
        TypeName methodReturn = no.sikt.graphitron.rewrite.model.RowsMethodShape
            .outerRowsReturnType(RECORD, rt, keyElement, isMapped);
        TypeName keysContainer = ParameterizedTypeName.get(isMapped ? setClass : LIST, keyElement);

        TypeName[] rowTypeArgs = new TypeName[arity];
        rowTypeArgs[0] = ClassName.get(Integer.class);
        rowTypeArgs[1] = ClassName.get(Integer.class);
        for (int i = 0; i < pks.size(); i++) {
            rowTypeArgs[i + 2] = pks.get(i).columnType();
        }
        TypeName rowType = ParameterizedTypeName.get(rowClass(arity), rowTypeArgs);
        TypeName recordRowType = ParameterizedTypeName.get(recordClass(arity), rowTypeArgs);
        TypeName projInputTableType = ParameterizedTypeName.get(TABLE, recordRowType);

        TypeName listX = ParameterizedTypeName.get(LIST, xRecord);
        TypeName listListX = ParameterizedTypeName.get(LIST, listX);
        TypeName listOfRowType = ParameterizedTypeName.get(LIST, rowType);
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        TypeName keyOrderType = ParameterizedTypeName.get(LIST, keyElement);
        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST, wildField);
        ClassName typeClass = ClassName.get(outputPackage + ".types", rt.returnTypeName());

        var body = CodeBlock.builder();
        // 1. Call the developer's @service method — returns real XRecords in the loader container.
        body.addStatement("$T fetched = $L", serviceReturn, serviceCall);

        // 2. Normalise to a parent-indexed List<List<XRecord>> (and, for the mapped container, the
        //    parent-key order so the result Map can be rebuilt). Single-cardinality returns collapse
        //    to a singleton-or-empty list per parent.
        if (isMapped) {
            body.addStatement("$T keyOrder = new $T<>(fetched.keySet())", keyOrderType, ARRAY_LIST);
            body.addStatement("$T perParent = new $T<>(keyOrder.size())", listListX, ARRAY_LIST);
            if (isList) {
                body.beginControlFlow("for ($T k : keyOrder)", keyElement)
                    .addStatement("perParent.add(fetched.get(k))")
                    .endControlFlow();
            } else {
                body.beginControlFlow("for ($T k : keyOrder)", keyElement)
                    .addStatement("$T rec = fetched.get(k)", xRecord)
                    .addStatement("perParent.add(rec == null ? $T.of() : $T.of(rec))", LIST, LIST)
                    .endControlFlow();
            }
        } else {
            if (isList) {
                body.addStatement("$T perParent = fetched", listListX);
            } else {
                body.addStatement("$T perParent = new $T<>(fetched.size())", listListX, ARRAY_LIST);
                body.beginControlFlow("for ($T rec : fetched)", xRecord)
                    .addStatement("perParent.add(rec == null ? $T.of() : $T.of(rec))", LIST, LIST)
                    .endControlFlow();
            }
        }

        // 3. Flatten to VALUES rows of (parentIdx, seq, returned-record PK…).
        body.addStatement("$T rows = new $T<>()", listOfRowType, ARRAY_LIST);
        body.addStatement("int seq = 0");
        body.beginControlFlow("for (int idx = 0; idx < perParent.size(); idx++)");
        body.beginControlFlow("for ($T rec : perParent.get(idx))", xRecord);
        // Cells delegated to the shared VALUES-cell authority so converter-backed target PKs
        // bind through the column's registered Converter DataType.
        CodeBlock liftOwnerExpr = CodeBlock.of("$T.$L", table.constantsClass(), table.javaFieldName());
        CodeBlock liftCells = ValuesJoinRowBuilder.cellsCode(
            pks, java.util.function.Function.identity(),
            CodeBlock.of("$T.inline(idx), $T.inline(seq)", DSL, DSL), liftOwnerExpr,
            (pk, i) -> CodeBlock.of("rec.get($L.$L)", liftOwnerExpr, pk.javaName()));
        body.addStatement("rows.add($T.row($L))", DSL, liftCells);
        body.addStatement("seq++");
        body.endControlFlow();
        body.endControlFlow();

        // 4. Per-parent buckets; run the identity-join re-projection only when there are keys
        //    (DSL.values rejects an empty row array). A parent the service returned nothing for
        //    keeps its empty bucket, which is the agreed drop-out semantics.
        body.addStatement("$T byParent = new $T<>(perParent.size())", listOfListOfRecord, ARRAY_LIST);
        body.beginControlFlow("for (int i = 0; i < perParent.size(); i++)")
            .addStatement("byParent.add(new $T<>())", ARRAY_LIST)
            .endControlFlow();
        body.beginControlFlow("if (!rows.isEmpty())");
        // Generic array creation is the one unavoidable unchecked cast (Java forbids new RowN<...>[]);
        // scoped to this one line, matching emitParentInputAndFkChain.
        body.add("@$T({$S, $S})\n", suppress, "unchecked", "rawtypes");
        body.addStatement("$T[] rowArray = ($T[]) rows.toArray(new $T[0])",
            rowType, rowType, rowClass(arity));
        var valuesAlias = CodeBlock.builder();
        valuesAlias.add("$S, $S, $S", "projectionInput", "idx", "seq");
        for (ColumnRef pk : pks) {
            valuesAlias.add(", $S", pk.sqlName());
        }
        body.addStatement("$T projectionInput = $T.values(rowArray).as($L)",
            projInputTableType, DSL, valuesAlias.build());
        body.addStatement("$T boundTable = $T.$L.as($S)",
            table.tableClass(), table.constantsClass(), table.javaFieldName(), stf.name());
        body.addStatement("$T selectFields = new $T<>($T.$$fields(env.getSelectionSet(), boundTable, env))",
            listOfField, ARRAY_LIST, typeClass);
        body.addStatement("selectFields.add(projectionInput.field(0, $T.class).as($S))",
            Integer.class, IDX_COLUMN);
        var onCond = CodeBlock.builder();
        for (int i = 0; i < pks.size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef pk = pks.get(i);
            onCond.add("boundTable.$L.eq($L)",
                pk.javaName(), parentInputFieldLookup("projectionInput", pk, table));
            if (i > 0) onCond.add(")");
        }
        var sel = CodeBlock.builder();
        sel.add("$T<$T> flat = dsl\n", ClassName.get("org.jooq", "Result"), RECORD);
        sel.indent();
        sel.add(".select(selectFields)\n");
        sel.add(".from(boundTable)\n");
        sel.add(".join(projectionInput).on($L)\n", onCond.build());
        // seq (VALUES column 1) is the service's flatten order; ordering the flat result by it keeps
        // each parent bucket in the order the service returned, since the scatter appends in fetch order.
        sel.add(".orderBy(projectionInput.field(1, $T.class))\n", Integer.class);
        sel.add(".fetch();\n");
        sel.unindent();
        body.add(sel.build());
        body.beginControlFlow("for ($T row : flat)", RECORD)
            .addStatement("byParent.get(row.get($S, $T.class)).add(row)", IDX_COLUMN, Integer.class)
            .endControlFlow();
        body.endControlFlow();

        // 5. Re-wrap into the loader's container shape.
        if (isList && !isMapped) {
            body.addStatement("return byParent");
        } else if (isList) {
            TypeName mapType = ParameterizedTypeName.get(mapClass, keyElement, listOfRecord);
            body.addStatement("$T out = new $T<>()", mapType, linkedHashMap);
            body.beginControlFlow("for (int i = 0; i < keyOrder.size(); i++)")
                .addStatement("out.put(keyOrder.get(i), byParent.get(i))")
                .endControlFlow();
            body.addStatement("return out");
        } else if (!isMapped) {
            body.addStatement("$T out = new $T<>(byParent.size())", listOfRecord, ARRAY_LIST);
            body.beginControlFlow("for ($T bucket : byParent)", listOfRecord)
                .addStatement("out.add(bucket.isEmpty() ? null : bucket.get(0))")
                .endControlFlow();
            body.addStatement("return out");
        } else {
            TypeName mapType = ParameterizedTypeName.get(mapClass, keyElement, RECORD);
            body.addStatement("$T out = new $T<>()", mapType, linkedHashMap);
            body.beginControlFlow("for (int i = 0; i < keyOrder.size(); i++)")
                .addStatement("$T bucket = byParent.get(i)", listOfRecord)
                .addStatement("out.put(keyOrder.get(i), bucket.isEmpty() ? null : bucket.get(0))")
                .endControlFlow();
            body.addStatement("return out");
        }

        return RowsMethodSkeleton.build(
            // Declaration name through the command-mint seam (R314): the child service-table
            // lift is always reentry, so this commits the load<X> MethodCommand.
            ctx.rowsDeclarationName(stf),
            methodReturn,
            keysContainer,
            ctx.graphitronContextCall(),
            new RowsMethodBody.Service(body.build(), true));
    }
}
