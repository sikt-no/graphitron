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
     */
    private record PreludeBindings(
        List<String> aliases,
        String terminalAlias,
        String firstAlias,
        JoinStep.FkJoin firstHop,
        TypeName keyElement,
        List<ColumnRef> pkCols
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
            CodeBlock.Builder body,
            String fieldName,
            BatchKey batchKey,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<JoinStep> joinPath,
            String jooqPackage) {
        TableRef terminalTable = returnType.table();
        ClassName tablesClass = ClassName.get(jooqPackage, "Tables");

        BatchKey.RowKeyed rowKeyed = (BatchKey.RowKeyed) batchKey;
        List<ColumnRef> pkCols = rowKeyed.parentKeyColumns();
        TypeName keyElement = GeneratorUtils.keyElementType(batchKey);

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
        // Classifier contract: joinPath is non-empty and its first step is an FkJoin. Empty paths
        // are rejected in BuildContext.parsePath; ConditionJoin-first paths are short-circuited
        // by unsupportedReason on each enclosing variant.
        JoinStep.FkJoin firstHop = (JoinStep.FkJoin) joinPath.get(0);

        // Empty-input short-circuit — before touching the DSL context.
        body.beginControlFlow("if (keys.isEmpty())");
        body.addStatement("return $T.of()", LIST);
        body.endControlFlow();

        body.addStatement("$T dsl = graphitronContext(env).getDslContext(env)",
            ClassName.get("org.jooq", "DSLContext"));

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
        for (int i = 0; i < pkCols.size(); i++) {
            rowArgs.add(", k.field$L()", i + 1);
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
        // declaration (joinPath.size() == 1).
        for (int i = 0; i < joinPath.size(); i++) {
            JoinStep.FkJoin fk = (JoinStep.FkJoin) joinPath.get(i);
            ClassName jooqTableClass = ClassName.get(
                jooqPackage + ".tables",
                fk.targetTable().javaClassName());
            body.addStatement("$T $L = $T.$L.as($S)",
                jooqTableClass, aliases.get(i), tablesClass, fk.targetTable().javaFieldName(),
                fieldName + "_" + aliases.get(i));
        }

        return new PreludeBindings(aliases, terminalAlias, firstAlias, firstHop, keyElement, pkCols);
    }

    /**
     * Builds the rows-method for a {@link ChildField.SplitTableField} or
     * {@link ChildField.SplitLookupTableField}. The returned {@link MethodSpec} is complete
     * (signature + body) and is added directly to the enclosing {@code *Fetchers} class.
     *
     * @param bkf  the batched field — must be one of {@link ChildField.SplitTableField},
     *             {@link ChildField.SplitLookupTableField}, {@link ChildField.RecordTableField},
     *             or {@link ChildField.RecordLookupTableField}. Other {@link BatchKeyField}
     *             leaves throw {@link IllegalArgumentException}.
     */
    public static MethodSpec buildRowsMethod(BatchKeyField bkf, String outputPackage, String jooqPackage) {
        if (bkf instanceof ChildField.SplitTableField stf) {
            return buildForSplitTable(stf, outputPackage, jooqPackage);
        }
        if (bkf instanceof ChildField.SplitLookupTableField slf) {
            return buildForSplitLookupTable(slf, outputPackage, jooqPackage);
        }
        if (bkf instanceof ChildField.RecordTableField rtf) {
            return buildForRecordTable(rtf, outputPackage, jooqPackage);
        }
        if (bkf instanceof ChildField.RecordLookupTableField rltf) {
            return buildForRecordLookupTable(rltf, outputPackage, jooqPackage);
        }
        throw new IllegalArgumentException(
            "SplitRowsMethodEmitter does not handle " + bkf.getClass().getSimpleName());
    }

    // -----------------------------------------------------------------------
    // SplitTableField
    // -----------------------------------------------------------------------

    private static MethodSpec buildForSplitTable(ChildField.SplitTableField stf, String outputPackage, String jooqPackage) {
        var stubReason = unsupportedReason(stf);
        if (stubReason.isPresent()) {
            return buildRuntimeStub(stf.rowsMethodName(), stf.batchKey(), stf.returnType(), stubReason.get(), outputPackage);
        }
        if (stf.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Single) {
            return buildSingleMethod(
                stf.name(), stf.rowsMethodName(), stf.returnType(),
                stf.joinPath(), stf.filters(), stf.batchKey(), outputPackage, jooqPackage);
        }
        if (stf.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn) {
            return buildConnectionMethod(
                stf.name(), stf.rowsMethodName(), stf.returnType(),
                stf.joinPath(), stf.filters(), stf.batchKey(), stf.orderBy(), conn, outputPackage, jooqPackage);
        }
        return buildListMethod(
            stf.name(), stf.rowsMethodName(), stf.returnType(),
            stf.joinPath(), stf.filters(), stf.batchKey(),
            /* lookupMapping */ null, outputPackage, jooqPackage);
    }

    /**
     * Returns the reason why this {@link ChildField.SplitTableField} cannot be emitted as a
     * working DataLoader rows method today — or empty if it is emittable. Shared between
     * {@link #buildForSplitTable} (runtime stub) and
     * {@code GraphitronSchemaValidator.validateVariantIsImplemented} (build-time error), so
     * the two stay in lock-step. Moving a branch from here to a real emitter body must
     * update this predicate in the same commit.
     */
    public static java.util.Optional<String> unsupportedReason(ChildField.SplitTableField stf) {
        if (JoinPathEmitter.hasConditionJoin(stf.joinPath())) {
            return java.util.Optional.of(
                "@splitQuery '" + stf.qualifiedName() + "' with a condition-join step cannot be "
                + "emitted until classification-vocabulary item 5 resolves condition-method target tables");
        }
        return java.util.Optional.empty();
    }

    // -----------------------------------------------------------------------
    // SplitLookupTableField (C2)
    // -----------------------------------------------------------------------

    private static MethodSpec buildForSplitLookupTable(ChildField.SplitLookupTableField slf, String outputPackage, String jooqPackage) {
        var stubReason = unsupportedReason(slf);
        if (stubReason.isPresent()) {
            return buildRuntimeStub(slf.rowsMethodName(), slf.batchKey(), slf.returnType(), stubReason.get(), outputPackage);
        }

        return buildListMethod(
            slf.name(), slf.rowsMethodName(), slf.returnType(),
            slf.joinPath(), slf.filters(), slf.batchKey(),
            slf.lookupMapping(), outputPackage, jooqPackage);
    }

    /**
     * Split* sibling of {@link #unsupportedReason(ChildField.SplitTableField)}. Same contract:
     * non-empty reason → field cannot be emitted today; empty → emittable.
     *
     * <p>Single-cardinality {@code @splitQuery @lookupKey} is rejected upstream at classifier
     * time ({@code FieldBuilder}'s {@code hasSplitQuery && hasLookupKey} arm), so this emitter
     * never sees a single-cardinality {@link ChildField.SplitLookupTableField}.
     */
    public static java.util.Optional<String> unsupportedReason(ChildField.SplitLookupTableField slf) {
        if (JoinPathEmitter.hasConditionJoin(slf.joinPath())) {
            return java.util.Optional.of(
                "@splitQuery @lookupKey '" + slf.qualifiedName() + "' with a condition-join step cannot be "
                + "emitted until classification-vocabulary item 5 resolves condition-method target tables");
        }
        return java.util.Optional.empty();
    }

    // -----------------------------------------------------------------------
    // RecordTableField
    // -----------------------------------------------------------------------

    private static MethodSpec buildForRecordTable(ChildField.RecordTableField rtf, String outputPackage, String jooqPackage) {
        var stubReason = unsupportedReason(rtf);
        if (stubReason.isPresent()) {
            return buildRuntimeStub(rtf.rowsMethodName(), rtf.batchKey(), rtf.returnType(), stubReason.get(), outputPackage);
        }
        return buildListMethod(
            rtf.name(), rtf.rowsMethodName(), rtf.returnType(),
            rtf.joinPath(), rtf.filters(), rtf.batchKey(),
            /* lookupMapping */ null, outputPackage, jooqPackage);
    }

    /**
     * Split* sibling of {@link #unsupportedReason(ChildField.SplitTableField)}. Same contract:
     * non-empty reason → field cannot be emitted today; empty → emittable.
     */
    public static java.util.Optional<String> unsupportedReason(ChildField.RecordTableField rtf) {
        boolean isList = rtf.returnType().wrapper().isList();
        if (!isList) {
            return java.util.Optional.of(
                "Single-cardinality RecordTableField on '" + rtf.qualifiedName()
                + "' not yet supported; list cardinality only.");
        }
        if (JoinPathEmitter.hasConditionJoin(rtf.joinPath())) {
            return java.util.Optional.of(
                "RecordTableField '" + rtf.qualifiedName() + "' with a condition-join step cannot be "
                + "emitted until classification-vocabulary item 5 resolves condition-method target tables");
        }
        return java.util.Optional.empty();
    }

    // -----------------------------------------------------------------------
    // RecordLookupTableField
    // -----------------------------------------------------------------------

    private static MethodSpec buildForRecordLookupTable(ChildField.RecordLookupTableField rltf, String outputPackage, String jooqPackage) {
        var stubReason = unsupportedReason(rltf);
        if (stubReason.isPresent()) {
            return buildRuntimeStub(rltf.rowsMethodName(), rltf.batchKey(), rltf.returnType(), stubReason.get(), outputPackage);
        }
        // Rows-method body is identical to SplitLookupTableField's — same BatchKey.RowKeyed +
        // LookupMapping shape, so buildListMethod handles both. The record-parent divergence
        // (backing-object accessor vs jOOQ-table-row accessor for key extraction) lives above
        // this seam, in TypeFetcherGenerator.buildRecordBasedDataFetcher.
        return buildListMethod(
            rltf.name(), rltf.rowsMethodName(), rltf.returnType(),
            rltf.joinPath(), rltf.filters(), rltf.batchKey(),
            rltf.lookupMapping(), outputPackage, jooqPackage);
    }

    /**
     * Split* sibling of {@link #unsupportedReason(ChildField.SplitTableField)}. Same contract:
     * non-empty reason → field cannot be emitted today; empty → emittable.
     */
    public static java.util.Optional<String> unsupportedReason(ChildField.RecordLookupTableField rltf) {
        boolean isList = rltf.returnType().wrapper().isList();
        if (!isList) {
            return java.util.Optional.of(
                "Single-cardinality RecordLookupTableField on '" + rltf.qualifiedName()
                + "' not yet supported; list cardinality only.");
        }
        if (JoinPathEmitter.hasConditionJoin(rltf.joinPath())) {
            return java.util.Optional.of(
                "RecordLookupTableField '" + rltf.qualifiedName() + "' with a condition-join step cannot be "
                + "emitted until classification-vocabulary item 5 resolves condition-method target tables");
        }
        return java.util.Optional.empty();
    }

    /**
     * Shared body emitter for list-cardinality Split* rows methods. For
     * {@link ChildField.SplitTableField} pass {@code lookupMapping = null}; for
     * {@link ChildField.SplitLookupTableField} pass its mapping and the emitter adds a second
     * VALUES derived-table JOIN narrowing on the {@code @lookupKey} args.
     */
    private static MethodSpec buildListMethod(
            String fieldName,
            String rowsMethodName,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<JoinStep> joinPath,
            List<WhereFilter> filters,
            BatchKey batchKey,
            LookupMapping lookupMapping,
            String outputPackage,
            String jooqPackage) {
        ClassName keysClass = ClassName.get(jooqPackage, "Keys");
        ClassName typeClass = ClassName.get(
            outputPackage + ".types",
            returnType.returnTypeName());

        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);

        var body = CodeBlock.builder();
        PreludeBindings p = emitParentInputAndFkChain(
            body, fieldName, batchKey, returnType, joinPath, jooqPackage);
        List<JoinStep> path = joinPath;
        List<String> aliases = p.aliases();
        String terminalAlias = p.terminalAlias();
        String firstAlias = p.firstAlias();
        JoinStep.FkJoin firstHop = p.firstHop();
        TypeName keyElement = p.keyElement();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);
        List<ColumnRef> pkCols = p.pkCols();

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
                prevAlias, keysClass, bridging.fkJavaConstant());
        }
        // JOIN parentInput on step 0's source columns (target/terminal side for list cardinality).
        // parentInput.field(n, Class<T>) returns Field<T>, matching the FK column's type in
        // .eq(...). Position mapping: index 0 is idx, indices 1..N are the parent PK columns
        // in the order declared by BatchKey.RowKeyed.parentKeyColumns(). ON rather than USING dodges
        // junction-column collisions, as Phase 2a C2 established.
        var onCond = CodeBlock.builder();
        for (int i = 0; i < firstHop.sourceColumns().size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef pk = pkCols.get(i);
            ClassName pkType = ClassName.bestGuess(pk.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($L, $T.class))",
                firstAlias,
                firstHop.sourceColumns().get(i).javaName(),
                i + 1, pkType);
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

        // WHERE: per-hop whereFilters + field-level filters.
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
                ArgCallEmitter.buildCallArgs(f.callParams(), f.className(), terminalAlias));
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
     * emitter uses {@code firstHop.targetColumns()} to address the column on {@code firstAlias}.
     * FK-vs-PK column types match by jOOQ invariant, so {@code pkCols.get(i).columnClass()}
     * (the BatchKey's column type) is the right cast target for the typed
     * {@code parentInput.field(i+1, Class<T>)} call.
     */
    private static MethodSpec buildSingleMethod(
            String fieldName,
            String rowsMethodName,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<JoinStep> joinPath,
            List<WhereFilter> filters,
            BatchKey batchKey,
            String outputPackage,
            String jooqPackage) {
        ClassName typeClass = ClassName.get(
            outputPackage + ".types",
            returnType.returnTypeName());

        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);

        var body = CodeBlock.builder();
        // Classifier guarantees single-hop (§1c rejects multi-hop single-cardinality), so the
        // shared prelude's FK-chain loop emits exactly one declaration.
        PreludeBindings p = emitParentInputAndFkChain(
            body, fieldName, batchKey, returnType, joinPath, jooqPackage);
        String firstAlias = p.firstAlias();
        JoinStep.FkJoin firstHop = p.firstHop();
        TypeName keyElement = p.keyElement();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);
        List<ColumnRef> pkCols = p.pkCols();

        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST, wildField);
        body.addStatement("$T selectFields = new $T<>($T.$$fields(env.getSelectionSet(), $L, env))",
            listOfField, ARRAY_LIST, typeClass, firstAlias);
        body.addStatement("selectFields.add(parentInput.field(0, $T.class).as($S))",
            Integer.class, IDX_COLUMN);

        // JOIN: terminal.<target_col> = parentInput.<fk_value>.
        // Single-cardinality: sourceColumns sit on the parent (not addressable via firstAlias);
        // targetColumns sit on the terminal and are the right reference.
        var onCond = CodeBlock.builder();
        for (int i = 0; i < firstHop.targetColumns().size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef parentCol = pkCols.get(i);
            ClassName colType = ClassName.bestGuess(parentCol.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($L, $T.class))",
                firstAlias,
                firstHop.targetColumns().get(i).javaName(),
                i + 1, colType);
            if (i > 0) onCond.add(")");
        }

        var sel = CodeBlock.builder();
        sel.add("$T<$T> flat = dsl\n", ClassName.get("org.jooq", "Result"), RECORD);
        sel.indent();
        sel.add(".select(selectFields)\n");
        sel.add(".from($L)\n", firstAlias);
        sel.add(".join(parentInput).on($L)\n", onCond.build());

        var where = CodeBlock.builder();
        where.add("$T.noCondition()", DSL);
        if (firstHop.whereFilter() != null) {
            where.add(".and($L)",
                JoinPathEmitter.emitTwoArgMethodCall(firstHop.whereFilter(), firstAlias, firstAlias));
        }
        for (WhereFilter f : filters) {
            where.add(".and($T.$L($L))",
                ClassName.bestGuess(f.className()), f.methodName(),
                ArgCallEmitter.buildCallArgs(f.callParams(), f.className(), firstAlias));
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
            String fieldName,
            String rowsMethodName,
            ReturnTypeRef.TableBoundReturnType returnType,
            List<JoinStep> joinPath,
            List<WhereFilter> filters,
            BatchKey batchKey,
            no.sikt.graphitron.rewrite.model.OrderBySpec orderBy,
            no.sikt.graphitron.rewrite.model.FieldWrapper.Connection conn,
            String outputPackage,
            String jooqPackage) {

        ClassName keysClass = ClassName.get(jooqPackage, "Keys");
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
        PreludeBindings p = emitParentInputAndFkChain(
            body, fieldName, batchKey, returnType, joinPath, jooqPackage);
        List<JoinStep> path = joinPath;
        List<String> aliases = p.aliases();
        String terminalAlias = p.terminalAlias();
        String firstAlias = p.firstAlias();
        JoinStep.FkJoin firstHop = p.firstHop();
        TypeName keyElement = p.keyElement();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);
        List<ColumnRef> pkCols = p.pkCols();

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
                prevAlias, keysClass, bridging.fkJavaConstant());
        }
        var onCond = CodeBlock.builder();
        for (int i = 0; i < firstHop.sourceColumns().size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef pk = pkCols.get(i);
            ClassName pkType = ClassName.bestGuess(pk.columnClass());
            onCond.add("$L.$L.eq(parentInput.field($L, $T.class))",
                firstAlias,
                firstHop.sourceColumns().get(i).javaName(),
                i + 1, pkType);
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
                ArgCallEmitter.buildCallArgs(f.callParams(), f.className(), terminalAlias));
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
        TypeName keyElement = GeneratorUtils.keyElementType(batchKey);
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
