package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
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
 * <p>Emitted bodies follow argres Phase 2b's shape:
 * <ol>
 *   <li>Empty-input short-circuit — returns {@code List.of()} without touching the DSL context.</li>
 *   <li>Parent-input {@code VALUES} table carrying {@code (idx, parent_pk...)} — one row per
 *       {@code keys[i]}. Rows are typed {@code Row<N+1><Integer, pkType1, pkType2, …>}, the
 *       corresponding typed {@link org.jooq.Table} carries {@link org.jooq.Record Record}&lt;N+1&gt;,
 *       and column access via {@code parentInput.fieldsRow().fieldK()} returns typed
 *       {@link org.jooq.Field Field}&lt;T&gt;. Arity is known at codegen time from
 *       {@link BatchKey.RowKeyed#keyColumns()}; generic array creation is the one unavoidable
 *       {@code @SuppressWarnings("unchecked")} per generated method.</li>
 *   <li>Key unpacking uses {@code k.field1()}…{@code k.fieldN()} — {@code Row1/Row2/…} expose
 *       their cells as typed {@code Field<T>} references (the inline {@code Field} jOOQ created
 *       when {@link GeneratorUtils#buildKeyExtraction} built the key via {@code DSL.row(record.get(col))}).
 *       The earlier plan's Decision 7 cited {@code value1()} calls, but those live on
 *       {@code Record1/Record2/…}, not on {@code Row} — {@code Row} is a schema construct, not
 *       a data carrier.</li>
 *   <li>FK chain aliases identical to G5 / Phase 2a.</li>
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
     * {@code Record}. Phase 2b C1 rejects parent PKs &gt;22 cols at codegen time.
     */
    private static ClassName rowClass(int arity) {
        return ClassName.get("org.jooq", "Row" + arity);
    }

    private static ClassName recordClass(int arity) {
        return ClassName.get("org.jooq", "Record" + arity);
    }

    /**
     * SELECT-projection alias for the parent-input {@code idx} column. Chosen to be
     * collision-unlikely with any GraphQL field name or {@code @field(name:)} mapping; see
     * argres Phase 2b plan Decision 6.
     */
    public static final String IDX_COLUMN = "__idx__";

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
     * target-column tuple by {@link BatchKey.LifterRowKeyed} construction). The prelude resolves
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
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "lifter-classifies-as-record-table-field",
        reliesOn = "The JOIN-on side-aware switch admits exactly FkJoin and LiftedHop. "
            + "BatchKeyLifterDirectiveResolver and FieldBuilder.deriveBatchKeyFromTypedAccessor "
            + "both guarantee the field classifies as RecordTableField or RecordLookupTableField "
            + "with a single-hop joinPath whose first step is LiftedHop, so the prelude can read "
            + "target accessors uniformly via WithTarget without per-accessor identity checks.")
    private static PreludeBindings emitParentInputAndFkChain(
            TypeFetcherEmissionContext ctx,
            CodeBlock.Builder body,
            String fieldName,
            BatchKey.RecordParentBatchKey batchKey,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<JoinStep> joinPath) {
        TableRef terminalTable = returnType.table();

        // Side-aware column list, polymorphically: RowKeyed's preludeKeyColumns delegates to
        // parentKeyColumns (catalog-FK side); LifterRowKeyed / AccessorKeyedSingle /
        // AccessorKeyedMany delegate to targetKeyColumns (target side via the LiftedHop). All
        // four produce RowN<...> of the same Java types as the JOIN target columns. The parameter
        // type RecordParentBatchKey makes "only the four prelude-reachable permits reach this
        // site" a compile-time guarantee instead of a switch with a default-throw arm.
        List<ColumnRef> pkCols = batchKey.preludeKeyColumns();
        TypeName keyElement = batchKey.keyElementType();

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
        // Classifier contract: joinPath is non-empty and its first step carries a target table
        // (FkJoin or LiftedHop, both implementing JoinStep.WithTarget). Empty paths are rejected
        // in BuildContext.parsePath; ConditionJoin-first paths are short-circuited by
        // unsupportedReason on each enclosing variant. The lifter path is single-hop by type
        // construction (BatchKey.LifterRowKeyed holds one LiftedHop, not a list), so the bridging
        // loop below never executes for it.
        JoinStep firstStep = joinPath.get(0);
        // Parent-side and target-side column lists, materialised through the WithTarget capability.
        // Both FkJoin and LiftedHop carry slots; FkSlot pairs source/target distinctly, LifterSlot
        // collapses them onto a single column. The accessors read direction-blind — synthesis-time
        // orientation guarantees sourceSide is on the parent table and targetSide on the target.
        if (firstStep instanceof JoinStep.ConditionJoin) {
            throw new IllegalStateException(
                "ConditionJoin cannot be the first hop of a Split/Record rows-method; "
                + "unsupportedReason should have short-circuited upstream");
        }
        JoinStep.WithTarget firstWithTarget = (JoinStep.WithTarget) firstStep;
        List<ColumnRef> joinOnCols = firstWithTarget.targetSideColumns();
        List<ColumnRef> joinOnParentCols = firstWithTarget.sourceSideColumns();

        // Empty-input short-circuit — before touching the DSL context.
        body.beginControlFlow("if (keys.isEmpty())");
        body.addStatement("return $T.of()", LIST);
        body.endControlFlow();

        body.addStatement("$T dsl = $L.getDslContext(env)",
            ClassName.get("org.jooq", "DSLContext"), ctx.graphitronContextCall());

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
        // RowN-keyed arms (RowKeyed and LifterRowKeyed) construct keys via DSL.row(value, ...)
        // where field<N>() returns a bind-parameter Field<T> wrapping the value, so passing
        // k.field<N>() into the parent VALUES row renders the value (not a column reference).
        //
        // RecordN-keyed arms (AccessorKeyedSingle / AccessorKeyedMany) construct keys via
        // record.into(table.col, ...): field<N>() returns the source-table column reference,
        // which would leak into the VALUES table as the column name instead of the value. We
        // pull the scalar via value<N>() and wrap it in DSL.val(...) so the argument typechecks
        // against jOOQ's Field-based DSL.row overload alongside the inline-i first argument.
        boolean isAccessor = batchKey instanceof BatchKey.AccessorKeyedSingle
                          || batchKey instanceof BatchKey.AccessorKeyedMany;
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

        // FK chain aliases — one declaration per hop. Single-cardinality emits a single
        // declaration (joinPath.size() == 1). Reads target accessors uniformly through the
        // WithTarget capability — FkJoin and LiftedHop both expose the same targetTable /
        // targetColumns / alias, so the loop body needs no per-accessor sealed switch.
        for (int i = 0; i < joinPath.size(); i++) {
            JoinStep.WithTarget step = (JoinStep.WithTarget) joinPath.get(i);
            ClassName jooqTableClass = step.targetTable().tableClass();
            body.addStatement("$T $L = $T.$L.as($S)",
                jooqTableClass, aliases.get(i), step.targetTable().constantsClass(), step.targetTable().javaFieldName(),
                fieldName + "_" + aliases.get(i));
        }

        return new PreludeBindings(aliases, terminalAlias, firstAlias, joinOnCols, joinOnParentCols, keyElement);
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
        var stubReason = unsupportedReason(stf);
        if (stubReason.isPresent()) {
            return buildRuntimeStub(stf.rowsMethodName(), stf.batchKey(), stf.returnType(), stubReason.get().message(), outputPackage);
        }
        if (stf.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Single) {
            return buildSingleMethod(
                ctx, stf.name(), stf.rowsMethodName(), stf.returnType(),
                stf.joinPath(), stf.filters(), stf.batchKey(), outputPackage);
        }
        if (stf.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
            return buildConnectionMethod(
                ctx, stf.name(), stf.rowsMethodName(), stf.returnType(),
                stf.joinPath(), stf.filters(), stf.batchKey(), stf.orderBy(), conn, outputPackage);
        }
        return buildListMethod(
            ctx, stf.name(), stf.rowsMethodName(), stf.returnType(),
            stf.joinPath(), stf.filters(), stf.batchKey(),
            /* lookupMapping */ null, outputPackage);
    }

    /**
     * Returns the reason why {@code field} cannot be emitted as a working DataLoader rows method
     * today — or empty if it is emittable. Shared between this emitter (runtime stub) and
     * {@code GraphitronSchemaValidator.validateVariantIsImplemented} (build-time error), so the
     * two stay in lock-step. Moving a branch from here to a real emitter body must update this
     * predicate in the same commit.
     *
     * <p>Dispatches via the {@link no.sikt.graphitron.rewrite.model.ConditionJoinReportable}
     * capability: the four ChildField variants that share the condition-join predicate
     * ({@link ChildField.SplitTableField}, {@link ChildField.SplitLookupTableField},
     * {@link ChildField.RecordTableField}, {@link ChildField.RecordLookupTableField}) all
     * implement it; non-implementing variants fall through to {@code Optional.empty()}. The
     * returned {@link Rejection.Deferred} carries an {@link Rejection.StubKey.EmitBlock} key
     * tagging the per-variant {@link Rejection.EmitBlockReason} value so downstream tooling
     * (LSP fix-its, watch-mode formatter) can distinguish "intra-variant emit-block" from
     * "stubbed leaf class" without parsing prose.
     */
    public static java.util.Optional<Rejection.Deferred> unsupportedReason(no.sikt.graphitron.rewrite.model.GraphitronField field) {
        if (!(field instanceof no.sikt.graphitron.rewrite.model.ConditionJoinReportable cjr)) {
            return java.util.Optional.empty();
        }
        if (!JoinPathEmitter.hasConditionJoin(cjr.joinPath())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(emitBlock(
            cjr.emitBlockReason(),
            cjr.displayLabel() + " '" + field.qualifiedName() + "' with a condition-join step cannot be "
            + "emitted until classification-vocabulary item 5 resolves condition-method target tables"));
    }

    private static Rejection.Deferred emitBlock(Rejection.EmitBlockReason reason, String summary) {
        return new Rejection.Deferred(summary, "", new Rejection.StubKey.EmitBlock(reason));
    }

    // -----------------------------------------------------------------------
    // SplitLookupTableField (C2)
    // -----------------------------------------------------------------------

    /** See {@link #buildForSplitTable} for the entry-point convention. */
    static MethodSpec buildForSplitLookupTable(TypeFetcherEmissionContext ctx, ChildField.SplitLookupTableField slf, String outputPackage) {
        var stubReason = unsupportedReason(slf);
        if (stubReason.isPresent()) {
            return buildRuntimeStub(slf.rowsMethodName(), slf.batchKey(), slf.returnType(), stubReason.get().message(), outputPackage);
        }

        return buildListMethod(
            ctx, slf.name(), slf.rowsMethodName(), slf.returnType(),
            slf.joinPath(), slf.filters(), slf.batchKey(),
            slf.lookupMapping(), outputPackage);
    }

    // -----------------------------------------------------------------------
    // RecordTableField
    // -----------------------------------------------------------------------

    /** See {@link #buildForSplitTable} for the entry-point convention. */
    static MethodSpec buildForRecordTable(TypeFetcherEmissionContext ctx, ChildField.RecordTableField rtf, String outputPackage) {
        var stubReason = unsupportedReason(rtf);
        if (stubReason.isPresent()) {
            return buildRuntimeStub(rtf.rowsMethodName(), rtf.batchKey(), rtf.returnType(), stubReason.get().message(), outputPackage);
        }
        // RecordTableField fields whose rows-method emits 1 record per key (today, only
        // AccessorKeyedMany: each accessor-derived element-PK key maps to exactly one
        // terminal record) route through buildSingleMethod, which produces the flat join +
        // scatterSingleByIdx shape required by the loader.loadMany dispatch (loader value
        // type Record, returning List<Record> 1:1 with keys, not List<List<Record>>). The
        // capability fold here matches TypeFetcherGenerator's scatterSingleByIdx helper-emission
        // gate; both ask the same uniform question of multiple variants.
        if (rtf.emitsSingleRecordPerKey()) {
            return buildSingleMethod(
                ctx, rtf.name(), rtf.rowsMethodName(), rtf.returnType(),
                rtf.joinPath(), rtf.filters(), rtf.batchKey(),
                outputPackage);
        }
        return buildListMethod(
            ctx, rtf.name(), rtf.rowsMethodName(), rtf.returnType(),
            rtf.joinPath(), rtf.filters(), rtf.batchKey(),
            /* lookupMapping */ null, outputPackage);
    }

    // -----------------------------------------------------------------------
    // RecordLookupTableField
    // -----------------------------------------------------------------------

    /** See {@link #buildForSplitTable} for the entry-point convention. */
    static MethodSpec buildForRecordLookupTable(TypeFetcherEmissionContext ctx, ChildField.RecordLookupTableField rltf, String outputPackage) {
        var stubReason = unsupportedReason(rltf);
        if (stubReason.isPresent()) {
            return buildRuntimeStub(rltf.rowsMethodName(), rltf.batchKey(), rltf.returnType(), stubReason.get().message(), outputPackage);
        }
        // Rows-method body is identical to SplitLookupTableField's — same BatchKey.RowKeyed +
        // LookupMapping shape, so buildListMethod handles both. The record-parent divergence
        // (backing-object accessor vs jOOQ-table-row accessor for key extraction) lives above
        // this seam, in TypeFetcherGenerator.buildRecordBasedDataFetcher.
        return buildListMethod(
            ctx, rltf.name(), rltf.rowsMethodName(), rltf.returnType(),
            rltf.joinPath(), rltf.filters(), rltf.batchKey(),
            rltf.lookupMapping(), outputPackage);
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
            BatchKey.RecordParentBatchKey batchKey,
            LookupMapping lookupMapping,
            String outputPackage) {
        ClassName typeClass = ClassName.get(
            outputPackage + ".types",
            returnType.returnTypeName());

        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);

        var body = CodeBlock.builder();
        PreludeBindings p = emitParentInputAndFkChain(ctx,
            body, fieldName, batchKey, returnType, joinPath);
        List<JoinStep> path = joinPath;
        List<String> aliases = p.aliases();
        String terminalAlias = p.terminalAlias();
        String firstAlias = p.firstAlias();
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

        // Lookup-input VALUES (SplitLookupTableField only). Uses the env-based helper shape from
        // Phase 1 — args live on env.getArgument(name) for a Split fetcher (not on a child
        // SelectedField as in Phase 2a's inline projection). The helper method name follows
        // Phase 2a's convention: <fieldName>InputRows.
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
        // Bridging hops: terminal back to step 0. path[i].fk joins path[i-1].targetTable into
        // the already-FROM'd chain.
        for (int i = path.size() - 1; i >= 1; i--) {
            JoinStep.FkJoin bridging = (JoinStep.FkJoin) path.get(i);
            String prevAlias = aliases.get(i - 1);
            sel.add(".join($L).onKey($T.$L)\n",
                prevAlias, bridging.fk().keysClass(), bridging.fk().constantName());
        }
        // JOIN parentInput on step 0's source columns (target/terminal side for list cardinality).
        // parentInput.field(sqlName, Class<T>) returns Field<T>, matching the FK column's type in
        // .eq(...). Slot pairing comes from the FK declaration: joinOnCols.get(i) is the
        // terminal-side column whose value equals the parent column joinOnParentCols.get(i)
        // (FK targetColumns on the catalog-FK path, identical to joinOnCols on the lifter path).
        // We resolve the parentInput field by sqlName + Java type rather than positional index,
        // because @node(keyColumns: [...]) ordering may differ from FK column ordering. ON
        // rather than USING dodges junction-column collisions, as Phase 2a C2 established.
        var onCond = CodeBlock.builder();
        for (int i = 0; i < joinOnCols.size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef parentCol = joinOnParentCols.get(i);
            ClassName parentColType = ClassName.bestGuess(parentCol.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($S, $T.class))",
                firstAlias,
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

        return MethodSpec.methodBuilder(rowsMethodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfListOfRecord)
            .addParameter(keysListType, "keys")
            .addParameter(ENV, "env")
            .addCode(body.build())
            .build();
    }

    /**
     * Single-cardinality sibling of {@link #buildListMethod} for {@link ChildField.SplitTableField}.
     * Classifier contract: single-hop, parent-holds-FK ({@link ChildField.SplitTableField}'s
     * {@code batchKey} carries the parent's FK columns per
     * {@code FieldBuilder.deriveSplitQueryBatchKey}). Emits a flat
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
            BatchKey.RecordParentBatchKey batchKey,
            String outputPackage) {
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
            body, fieldName, batchKey, returnType, joinPath);
        String firstAlias = p.firstAlias();
        // Two routes reach this method: SplitTableField single-cardinality (FkJoin first hop,
        // parent-holds-FK) and RecordTableField with AccessorKeyedMany (LiftedHop first hop,
        // key-equals-target-PK). Both implement WithTarget; the column-reference reads
        // uniformly. The whereFilter slot is FkJoin-only and is conditional below.
        JoinStep.WithTarget firstHop = (JoinStep.WithTarget) joinPath.get(0);
        TypeName keyElement = p.keyElement();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);

        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST, wildField);
        body.addStatement("$T selectFields = new $T<>($T.$$fields(env.getSelectionSet(), $L, env))",
            listOfField, ARRAY_LIST, typeClass, firstAlias);
        body.addStatement("selectFields.add(parentInput.field(0, $T.class).as($S))",
            Integer.class, IDX_COLUMN);

        // JOIN: terminal.<slot.targetSide()> = parentInput.<slot.sourceSide()>.
        // Both FkJoin and LiftedHop expose slots() through WithTarget; FkSlot pairs source/target
        // distinctly while LifterSlot collapses both onto a single column by construction (the
        // DataLoader key tuple IS the target-column tuple). The slot-iteration loop reads
        // direction-blind regardless of which permit the firstHop carries — synthesis-time
        // orientation guarantees sourceSide is on the parent table and targetSide on the terminal.
        // The parentInput field is resolved by sqlName + Java type rather than positional index,
        // sidestepping @node(keyColumns: [...]) vs FK column ordering mismatches.
        var onCond = CodeBlock.builder();
        int slotIdx = 0;
        for (var slot : firstHop.slots()) {
            if (slotIdx > 0) onCond.add(".and(");
            ColumnRef parentCol = slot.sourceSide();
            ClassName colType = ClassName.bestGuess(parentCol.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($S, $T.class))",
                firstAlias,
                slot.targetSide().javaName(),
                parentCol.sqlName(), colType);
            if (slotIdx > 0) onCond.add(")");
            slotIdx++;
        }

        var sel = CodeBlock.builder();
        sel.add("$T<$T> flat = dsl\n", ClassName.get("org.jooq", "Result"), RECORD);
        sel.indent();
        sel.add(".select(selectFields)\n");
        sel.add(".from($L)\n", firstAlias);
        sel.add(".join(parentInput).on($L)\n", onCond.build());

        var where = CodeBlock.builder();
        where.add("$T.noCondition()", DSL);
        // whereFilter is FkJoin-only (the accessor / lifter paths carry no per-step WHERE).
        if (firstHop instanceof JoinStep.FkJoin fk && fk.whereFilter() != null) {
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

        return MethodSpec.methodBuilder(rowsMethodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(keysListType, "keys")
            .addParameter(ENV, "env")
            .addCode(body.build())
            .build();
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
            BatchKey.RecordParentBatchKey batchKey,
            no.sikt.graphitron.rewrite.model.OrderBySpec orderBy,
            no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn,
            String outputPackage) {

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
            body, fieldName, batchKey, returnType, joinPath);
        List<JoinStep> path = joinPath;
        List<String> aliases = p.aliases();
        String terminalAlias = p.terminalAlias();
        String firstAlias = p.firstAlias();
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
                    sortParts.add("$L.$L.$L()", terminalAlias, col.column().javaName(), fixed.jooqMethodName());
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
        body.addStatement("selectFields.add(idxField.as($S))", "__idx__");
        body.addStatement("selectFields.add($T.rowNumber().over($T.partitionBy(idxField).orderBy(page.effectiveOrderBy())).as($S))",
            DSL, DSL, "__rn__");

        // Inner windowed SELECT — attaches .orderBy()/.seek() for cursor-driven filtering; the
        // OS-level seek predicate falls in as WHERE, filtering BEFORE ROW_NUMBER() is computed.
        var inner = CodeBlock.builder();
        inner.add("$T<?> ranked = dsl\n", TABLE);
        inner.indent();
        inner.add(".select(selectFields)\n");
        inner.add(".from($L)\n", terminalAlias);
        for (int i = path.size() - 1; i >= 1; i--) {
            JoinStep.FkJoin bridging = (JoinStep.FkJoin) path.get(i);
            String prevAlias = aliases.get(i - 1);
            inner.add(".join($L).onKey($T.$L)\n",
                prevAlias, bridging.fk().keysClass(), bridging.fk().constantName());
        }
        // joinOnCols: FkJoin.sourceColumns() on the catalog-FK path or LiftedHop.targetColumns()
        // on the lifter path; both addressable via firstAlias on the terminal side.
        // joinOnParentCols pairs slot-by-slot with joinOnCols and identifies which parent column
        // each terminal-side column references (FK targetColumns or, for the lifter path, the
        // same target-column tuple). The parentInput field is resolved by sqlName + Java type
        // rather than positional index, so an @node(keyColumns: [...]) ordering that disagrees
        // with the FK column ordering does not mis-pair column types.
        var onCond = CodeBlock.builder();
        for (int i = 0; i < joinOnCols.size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef parentCol = joinOnParentCols.get(i);
            ClassName parentColType = ClassName.bestGuess(parentCol.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($S, $T.class))",
                firstAlias,
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
            "__rn__", Integer.class, DSL);
        outer.add(".fetch();\n");
        outer.unindent();
        body.add(outer.build());

        body.addStatement("return scatterConnectionByIdx(flat, keys.size(), page)");

        return MethodSpec.methodBuilder(rowsMethodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfConnectionResult)
            .addParameter(keysListType, "keys")
            .addParameter(ENV, "env")
            .addCode(body.build())
            .build();
    }

    // -----------------------------------------------------------------------
    // Stubs: runtime (body-throws) and codegen (emitter-throws)
    // -----------------------------------------------------------------------

    /**
     * Runtime stub: signature is correct (same as the real rows method), body throws so the
     * regression surfaces the first time the variant is actually called. Used for cardinality,
     * ConditionJoin, and empty-joinPath branches that C1/C2 don't emit real bodies for.
     */
    private static MethodSpec buildRuntimeStub(String methodName, BatchKey batchKey,
            ReturnTypeRef.TableBoundReturnType returnType, String reason, String outputPackage) {
        TypeName keyElement = batchKey.keyElementType();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);
        TypeName valueType;
        if (returnType.wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            ClassName connectionResultClass = ClassName.get(
                outputPackage + ".util", "ConnectionResult");
            valueType = ParameterizedTypeName.get(LIST, connectionResultClass);
        } else {
            boolean isList = returnType.wrapper().isList();
            valueType = isList
                ? ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD))
                : ParameterizedTypeName.get(LIST, RECORD);
        }
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(valueType)
            .addParameter(keysListType, "keys")
            .addParameter(ENV, "env")
            .addStatement("throw new $T($S)", UnsupportedOperationException.class, reason)
            .build();
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
