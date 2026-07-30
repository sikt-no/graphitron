package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.RowsMethodBody;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.render.ProjectionCall;
import no.sikt.graphitron.render.ValuesJoinRowBuilder;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.LIST;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.RECORD;

/**
 * The DataLoader seam's per-fetcher-class emission companions. The batched table, lookup and
 * pivot children's rows methods render through the launcher-command path (the batched launcher
 * renderer over the launcher producer's rows); what remains here is what those methods and the
 * service arms share per class:
 *
 * <ul>
 *   <li>The scatter helpers turning a flat {@code __idx__}-keyed result into the per-key shapes
 *       the loaders expect: {@link #buildScatterByIdxHelper()},
 *       {@link #buildScatterSingleByIdxHelper()}, {@link #buildScatterConnectionByIdxHelper},
 *       and the lookup arm's {@link #buildEmptyScatterHelper()}.</li>
 *   <li>The {@code RowN}-key scalar extraction, {@link #buildParentKeyCellValueHelper()}.</li>
 *   <li>The {@code @service} table lift-back rows method, {@link #buildServiceTableLift}, the
 *       one rows-method body still emitted here.</li>
 * </ul>
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
    // (__name__) is collision avoidance: these names live in the result-set column namespace
    // alongside real table columns, which the consumer's DB schema controls, so the wrapping
    // keeps a synthetic alias from colliding with a real column named `idx` or `rn`. They reach
    // generated code only as string literals (.as("__idx__"), r.get("__rn__")), never as Java
    // identifiers, so DunderFreeEmissionPipelineTest's identifier scan leaves them alone.

    /**
     * SELECT-projection alias for the parent-input {@code idx} column that drives the Java-side
     * scatter back to the originating parent row. Single-sourced on
     * {@link no.sikt.graphitron.command.ReservedAliases#IDX}: the batched launcher renderer
     * writes the alias, the scatter helpers emitted here read it back.
     */
    public static final String IDX_COLUMN = no.sikt.graphitron.command.ReservedAliases.IDX;

    /**
     * SELECT-projection alias for the windowed {@code ROW_NUMBER()} column; the outer SELECT
     * filters {@code RN_COLUMN <= page.limit()} to enforce the per-partition page limit.
     * Single-sourced on {@link no.sikt.graphitron.command.ReservedAliases#ROW_NUMBER}.
     */
    public static final String RN_COLUMN = no.sikt.graphitron.command.ReservedAliases.ROW_NUMBER;

    private SplitRowsMethodEmitter() {}

    /**
     * The typed {@code <valuesLocal>.field(...)} lookup for one JOIN-predicate slot:
     * {@code <valuesLocal>.field("<sqlName>", Tables.<OWNER>.<COL>.getDataType())}. Paired with
     * the VALUES cell construction ({@link ValuesJoinRowBuilder#cellsCode}) so the looked-up
     * {@code Field}'s type metadata matches the cell binds (the derived table's column SQL types
     * come from the cells; the lookup's {@code DataType} keeps the predicate's Java-side view
     * faithful and symmetric).
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
     * holds; for {@code @sourceRow} lifter keys it is a documented contract: a lifter that
     * builds its {@code RowN} from column references (not scalar values) gets this diagnostic
     * instead of a silently mistyped bind. Emitted once per fetcher class that has any
     * Row-keyed parent-input rows method (gate in {@link TypeFetcherGenerator}).
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

    // -----------------------------------------------------------------------
    // Scatter helpers, emitted once per fetcher class that has any Split* field.
    // -----------------------------------------------------------------------

    /**
     * Builds the private static {@code emptyScatter(int keyCount)} helper returning a
     * pre-populated list of empty sublists. Used by the BatchedLookupTableField rows method's
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
     * {@code ConnectionResult} that shares the batch's
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
        return buildScatterConnectionByIdxHelper(outputPackage, false);
    }

    /**
     * Canonical form carrying the tenancy bit: in a multi-tenant build the helper takes the rows
     * method's routed {@code DSLContext} and binds it onto each per-parent carrier, so the lazy
     * resolvers aggregate against the source the page rows came from.
     */
    public static MethodSpec buildScatterConnectionByIdxHelper(String outputPackage, boolean multiTenant) {
        TypeName resultRecord = ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), RECORD);
        ClassName connectionResultClass = ClassName.get(
            outputPackage + ".util", "ConnectionResult");
        ClassName pageRequestClass = ClassName.get(
            outputPackage + ".util", "ConnectionHelper", "PageRequest");
        TypeName tableWildcard = ParameterizedTypeName.get(TABLE, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        TypeName listOfConnectionResult = ParameterizedTypeName.get(LIST, connectionResultClass);
        var helper = MethodSpec.methodBuilder("scatterConnectionByIdx")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfConnectionResult)
            .addParameter(resultRecord, "flat")
            .addParameter(int.class, "keyCount")
            .addParameter(pageRequestClass, "page")
            .addParameter(tableWildcard, "countSource");
        if (multiTenant) {
            helper.addParameter(ClassName.get("org.jooq", "DSLContext"), "dsl");
        }
        return helper
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
                        + " countSource.field($S, $T.class).eq($T.inline(i))"
                        + (multiTenant ? ", dsl" : "") + "))",
                    connectionResultClass, IDX_COLUMN, Integer.class, DSL)
                .endControlFlow()
                .addStatement("return out")
                .build())
            .build();
    }

    // -----------------------------------------------------------------------
    // ServiceTableField: lift-back projection
    // -----------------------------------------------------------------------

    /**
     * Rows-method for a {@link ChildField.ServiceTableField}: the condensed
     * {@code ServiceRecordField} -> record-sourced {@code BatchedTableField} shape. The developer's {@code @service}
     * method produces real {@code XRecord}s (the {@code serviceCall} expression, returning the
     * loader's {@code Map}/{@code List} container of {@code XRecord}); this method lifts those
     * back by extracting each returned record's primary key, re-projecting the bound table on
     * that key by identity through {@code Type.$project(...)}, and re-wrapping the projected
     * {@code Record}s into the same container shape. Scalar sub-fields and {@code @reference}
     * multiset sub-fields both resolve off the projected record, where the verbatim service
     * return carried only stored columns.
     *
     * <p>The only difference from the record-sourced {@code BatchedTableField} arm's element-PK
     * re-projection is timing: there the records are in hand at fetch time and the DataLoader key is the element
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
        // 1. Call the developer's @service method: returns real XRecords in the loader container.
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
        // scoped to this one line, matching the batched launcher renderer's parent-input VALUES.
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
        body.addStatement("$T selectFields = new $T<>($L)",
            listOfField, ARRAY_LIST, ProjectionCall.fromEnvSelection(typeClass, "boundTable"));
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
            // Declaration name through the command-mint seam: the child service-table
            // lift is always reentry, so this commits the load<X> MethodCommand.
            ctx.rowsDeclarationName(stf),
            methodReturn,
            keysContainer,
            TenantDslEmitter.resolve(ctx, stf, outputPackage).declaration(),
            new RowsMethodBody.Service(body.build(), true));
    }
}
