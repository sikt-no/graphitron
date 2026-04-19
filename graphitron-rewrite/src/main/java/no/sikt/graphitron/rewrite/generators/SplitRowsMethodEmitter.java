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
 *       {@code keys[i]}. Keys are unpacked by codegen-time arity via {@code Row2<…>.field1()} /
 *       {@code .field2()} — jOOQ's {@code Row} exposes its cells as typed {@code Field} references,
 *       and {@code DSL.row(Field, Field, …)} happily accepts them. Attempted runtime introspection
 *       via {@code Row.intoArray()} or a hypothetical {@code .value1()} was rejected at
 *       implementation time (the {@code value*()} accessors live on {@code Record1/Record2/…},
 *       not on {@code Row1/Row2/…} — the earlier plan wording got the API wrong).</li>
 *   <li>FK chain aliases identical to G5 / Phase 2a.</li>
 *   <li>{@code .select($fields + parentInput.field("idx").as("__idx__"))} — the {@code __idx__}
 *       column drives the Java-side scatter, see {@link #IDX_COLUMN}.</li>
 *   <li>Explicit {@code ON} predicate joining the first FK hop to {@code parentInput} — inherits
 *       the USING→ON lesson from {@link InlineLookupTableFieldEmitter} (junction tables re-expose
 *       the FK column and would collide under USING). The lookup uses the typed parent table
 *       field reference ({@code parentInput.field(Tables.FILM.FILM_ID)}) rather than a bare string
 *       name, so the resulting {@code Field<T>} matches the FK column's type in {@code .eq(...)}.</li>
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
    private static final ArrayTypeName ROW_ARRAY = ArrayTypeName.of(ROW_N);

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

        // Parent-input VALUES rows. Keys are RowN-typed; unpack by codegen-time arity via
        // field1()/field2()/… calls. Each fieldN() returns a Field<T> holding the inline value
        // we placed there via buildKeyExtraction's DSL.row(Record.get(col)) shape. The arg arity
        // must not resolve to DSL.row(T1, T2, …) — those return Row2/Row3/… which don't extend
        // RowN. Instead we build an Object[] cell array and pass it to DSL.row(Object...), the
        // RowN-producing varargs overload. Same trick as LookupValuesJoinEmitter.addRowBuildingCore.
        body.addStatement("$T parentRows = new $T[keys.size()]", ROW_ARRAY, ROW_N);
        body.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        body.addStatement("$T k = keys.get(i)", keyElement);
        var cells = CodeBlock.builder();
        cells.add("$T.inline(i)", DSL);
        for (int i = 0; i < pkCols.size(); i++) {
            cells.add(", k.field$L()", i + 1);
        }
        body.addStatement("$T[] cells = new $T[] { $L }", Object.class, Object.class, cells.build());
        body.addStatement("parentRows[i] = $T.row(cells)", DSL);
        body.endControlFlow();

        // VALUES derived-table alias: "parentInput", "idx", pk_col1_sqlName, pk_col2_sqlName, …
        var parentInputAlias = CodeBlock.builder();
        parentInputAlias.add("$S, $S", "parentInput", "idx");
        for (var col : pkCols) {
            parentInputAlias.add(", $S", col.sqlName());
        }
        TypeName wildcardTable = ParameterizedTypeName.get(TABLE,
            WildcardTypeName.subtypeOf(Object.class));
        body.addStatement("$T parentInput = $T.values(parentRows).as($L)",
            wildcardTable, DSL, parentInputAlias.build());

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

        // Projection: $fields(env.getSelectionSet(), terminalAlias, env) + parentInput.idx as __idx__
        // env.getSelectionSet() is the child-selection for the Split field itself — exactly what
        // a SelectedField.getSelectionSet() would return, so the rows method signature does not
        // need a separate SelectedField parameter. See the "dropped sel parameter" commit message.
        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST, wildField);
        body.addStatement("$T selectFields = new $T<>($T.$$fields(env.getSelectionSet(), $L, env))",
            listOfField, ARRAY_LIST, typeClass, terminalAlias);
        body.addStatement("selectFields.add(($T) parentInput.field($S).as($S))",
            wildField, "idx", IDX_COLUMN);

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
        // Look up the parentInput field via the typed parent-table Field reference — returns
        // Field<T>, which matches the FK column's type in .eq(...). Looking up by bare name
        // returns Field<?> which the compiler cannot narrow. ON rather than USING dodges
        // junction-column collisions, as Phase 2a C2 established.
        ClassName parentTablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
        var onCond = CodeBlock.builder();
        for (int i = 0; i < firstHop.sourceColumns().size(); i++) {
            if (i > 0) onCond.add(".and(");
            onCond.add("$L.$L.eq(parentInput.field($T.$L.$L))",
                firstAlias,
                firstHop.sourceColumns().get(i).javaName(),
                parentTablesClass, parentTable.javaFieldName(),
                firstHop.targetColumns().get(i).javaName());
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
