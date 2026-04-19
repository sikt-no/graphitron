package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
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

    private static final ClassName ROW_N = ClassName.get("org.jooq", "RowN");
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
     * Builds the rows-method for a {@link ChildField.SplitTableField} or
     * {@link ChildField.SplitLookupTableField}. The returned {@link MethodSpec} is complete
     * (signature + body) and is added directly to the enclosing {@code *Fetchers} class.
     *
     * @param bkf          the Split* field — must be a {@link ChildField.SplitTableField} or
     *                     {@link ChildField.SplitLookupTableField} (C2). Other
     *                     {@link BatchKeyField} leaves throw {@link IllegalArgumentException}.
     * @param parentTable  the table-bound parent's {@link TableRef}
     */
    public static MethodSpec buildRowsMethod(BatchKeyField bkf, TableRef parentTable) {
        if (bkf instanceof ChildField.SplitTableField stf) {
            return buildForSplitTable(stf, parentTable);
        }
        if (bkf instanceof ChildField.SplitLookupTableField slf) {
            // C2 stub — the lookup branch adds a second VALUES+ON join on top of C1's shape.
            return buildCodegenStub(slf.rowsMethodName(), slf, bkf.batchKey(), slf.returnType(),
                "SplitLookupTableField rows-method emission lands in argres Phase 2b C2");
        }
        throw new IllegalArgumentException(
            "SplitRowsMethodEmitter does not handle " + bkf.getClass().getSimpleName());
    }

    // -----------------------------------------------------------------------
    // SplitTableField
    // -----------------------------------------------------------------------

    private static MethodSpec buildForSplitTable(ChildField.SplitTableField stf, TableRef parentTable) {
        ReturnTypeRef.TableBoundReturnType returnType = stf.returnType();
        boolean isList = returnType.wrapper().isList();

        // Single cardinality with parentHoldsFk=true requires the parent table in the JOIN
        // chain (parentInput carries parent PK, but the FK hop connects parent FK → target PK
        // — we need an extra hop through the parent table). Deferred to a follow-up.
        if (!isList) {
            return buildRuntimeStub(stf,
                "Single-cardinality @splitQuery on '" + stf.qualifiedName()
                + "' not yet supported; list cardinality is the Phase 2b C1 scope. "
                + "Single-cardinality requires joining the parent table to bridge parent PK to parent FK.");
        }
        if (JoinPathEmitter.hasConditionJoin(stf.joinPath())) {
            return buildRuntimeStub(stf,
                "@splitQuery '" + stf.qualifiedName() + "' with a condition-join step cannot be "
                + "emitted until classification-vocabulary item 5 resolves condition-method target tables");
        }
        if (stf.joinPath().isEmpty()) {
            return buildRuntimeStub(stf,
                "@splitQuery '" + stf.qualifiedName() + "' requires a @reference path — "
                + "Phase 2b C1 scope does not support path-less batched splits");
        }

        return buildListMethod(stf, parentTable);
    }

    private static MethodSpec buildListMethod(ChildField.SplitTableField stf, TableRef parentTable) {
        TableRef terminalTable = stf.returnType().table();
        ClassName tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
        ClassName keysClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Keys");
        ClassName typeClass = ClassName.get(
            RewriteConfig.outputPackage() + ".rewrite.types",
            stf.returnType().returnTypeName());

        BatchKey.RowKeyed batchKey = (BatchKey.RowKeyed) stf.batchKey();
        List<ColumnRef> pkCols = batchKey.keyColumns();
        TypeName keyElement = GeneratorUtils.keyElementType(batchKey);
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);

        // Typed RowN+1 and RecordN+1 for parentInput: idx + parent PK columns. Arity known at
        // codegen time, capped at 22 (jOOQ's typed Row/Record classes).
        int parentRowArity = pkCols.size() + 1;
        if (parentRowArity > 22) {
            throw new IllegalStateException(
                "Parent PK arity " + pkCols.size() + " + idx exceeds jOOQ's typed Row/Record arity limit (22)");
        }
        TypeName[] parentRowTypeArgs = new TypeName[parentRowArity];
        parentRowTypeArgs[0] = ClassName.get(Integer.class);  // idx
        for (int i = 0; i < pkCols.size(); i++) {
            parentRowTypeArgs[i + 1] = ClassName.bestGuess(pkCols.get(i).columnClass());
        }
        TypeName parentRowType = ParameterizedTypeName.get(rowClass(parentRowArity), parentRowTypeArgs);
        TypeName parentRecordType = ParameterizedTypeName.get(recordClass(parentRowArity), parentRowTypeArgs);
        TypeName parentInputTableType = ParameterizedTypeName.get(TABLE, parentRecordType);

        List<JoinStep> path = stf.joinPath();
        List<String> aliases = JoinPathEmitter.generateAliases(path, terminalTable);
        String terminalAlias = aliases.get(aliases.size() - 1);
        String firstAlias = aliases.get(0);
        JoinStep.FkJoin firstHop = (JoinStep.FkJoin) path.get(0);

        var body = CodeBlock.builder();

        // Empty-input short-circuit — before touching the DSL context.
        body.beginControlFlow("if (keys.isEmpty())");
        body.addStatement("return $T.of()", LIST);
        body.endControlFlow();

        body.addStatement("var dsl = graphitronContext(env).getDslContext(env)");

        // Parent-input VALUES rows — fully typed. One Row<N+1><Integer, pkType1, …> per key[i].
        // Generic array creation is the one unavoidable unchecked cast: Java forbids
        //   new Row2<Integer, Integer>[n]
        // so we cast a raw Row<N+1>[] up to the typed array. Scoped to this one line.
        // DSL.row(Field<T1>, Field<T2>, …) picks the typed Row<N> overload (not Row(Object...)
        // which would return untyped RowN), so we keep type info from DSL.inline(i) and
        // k.fieldJ() all the way into parentRows[i].
        body.add("@$T($S)\n", ClassName.get("java.lang", "SuppressWarnings"), "unchecked");
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
        // DSL.values(Row<N>... rows) returns Table<Record<N>> — typed through to field access.
        var parentInputAlias = CodeBlock.builder();
        parentInputAlias.add("$S, $S", "parentInput", "idx");
        for (var col : pkCols) {
            parentInputAlias.add(", $S", col.sqlName());
        }
        body.addStatement("$T parentInput = $T.values(parentRows).as($L)",
            parentInputTableType, DSL, parentInputAlias.build());

        // FK chain aliases — declare terminal first (FROM target), then each bridging hop.
        for (int i = 0; i < path.size(); i++) {
            JoinStep.FkJoin fk = (JoinStep.FkJoin) path.get(i);
            ClassName jooqTableClass = ClassName.get(
                RewriteConfig.getGeneratedJooqPackage() + ".tables",
                fk.targetTable().javaClassName());
            body.addStatement("$T $L = $T.$L.as($S)",
                jooqTableClass, aliases.get(i), tablesClass, fk.targetTable().javaFieldName(),
                stf.name() + "_" + aliases.get(i));
        }

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
        // in the order declared by BatchKey.RowKeyed.keyColumns(). ON rather than USING dodges
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
        for (WhereFilter f : stf.filters()) {
            where.add(".and($T.$L($L))",
                ClassName.bestGuess(f.className()), f.methodName(),
                ArgCallEmitter.buildCallArgs(f.callParams(), f.className()));
        }
        sel.add(".where($L)\n", where.build());
        sel.add(".fetch();\n");
        sel.unindent();
        body.add(sel.build());

        body.addStatement("return scatterByIdx(flat, keys.size())");

        return MethodSpec.methodBuilder(stf.rowsMethodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfListOfRecord)
            .addParameter(keysListType, "keys")
            .addParameter(ENV, "env")
            .addCode(body.build())
            .build();
    }

    // -----------------------------------------------------------------------
    // Stubs: runtime (body-throws) and codegen (emitter-throws)
    // -----------------------------------------------------------------------

    /** Runtime stub: the signature is correct, body throws so the regression surfaces on call. */
    private static MethodSpec buildRuntimeStub(ChildField.SplitTableField stf, String reason) {
        boolean isList = stf.returnType().wrapper().isList();
        TypeName keyElement = GeneratorUtils.keyElementType(stf.batchKey());
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);
        TypeName valueType = isList
            ? ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD))
            : ParameterizedTypeName.get(LIST, RECORD);
        return MethodSpec.methodBuilder(stf.rowsMethodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(valueType)
            .addParameter(keysListType, "keys")
            .addParameter(ENV, "env")
            .addStatement("throw new $T($S)", UnsupportedOperationException.class, reason)
            .build();
    }

    /** Codegen stub: used when a branch is not yet implemented and we want the signature right. */
    private static MethodSpec buildCodegenStub(String methodName, BatchKeyField bkf, BatchKey batchKey,
            ReturnTypeRef.TableBoundReturnType returnType, String reason) {
        boolean isList = returnType.wrapper().isList();
        TypeName keyElement = GeneratorUtils.keyElementType(batchKey);
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyElement);
        TypeName valueType = isList
            ? ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD))
            : ParameterizedTypeName.get(LIST, RECORD);
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
}
