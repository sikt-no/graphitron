package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.LIST;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.RECORD;

/**
 * The DataLoader seam's per-fetcher-class emission companions. Every rows-method body renders
 * through the launcher-command path now (the launcher renderer over the launcher producer's
 * rows); what remains here is what those methods share per fetchers class:
 *
 * <ul>
 *   <li>The scatter helpers turning a flat {@code __idx__}-keyed result into the per-key shapes
 *       the loaders expect: {@link #buildScatterByIdxHelper()},
 *       {@link #buildScatterSingleByIdxHelper()}, {@link #buildScatterConnectionByIdxHelper},
 *       and the lookup arm's {@link #buildEmptyScatterHelper()}.</li>
 *   <li>The {@code RowN}-key scalar extraction, {@link #buildParentKeyCellValueHelper()}.</li>
 * </ul>
 */
public final class SplitRowsMethodEmitter {

    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");
    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");

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
     * Builds the private static {@code parentKeyCellValue(Field<?>)} helper that extracts the
     * scalar value out of a {@code RowN}-shaped DataLoader key's cell. {@code RowN} keys are
     * constructed via {@code DSL.row(value, ...)}, which wraps each scalar in a bind
     * {@code Param}; jOOQ's {@code Row} exposes cells only as {@code Field}s, so the value is
     * recovered through the {@code Param} narrowing. Every key is generator-built, so the cast
     * holds; a key built from column references would get this diagnostic instead of a silently
     * mistyped bind. Emitted once per fetcher class that has any
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
     * pre-populated list of empty sublists. Used by the correlated-lookup rows method's
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
     * Root-lookup sibling of {@link #buildScatterSingleByIdxHelper}. Builds the private static
     * {@code scatterLookupByIdx(Result<Record>, int)} helper that turns the flat join result into
     * a {@code List<Record>} holding one slot per input key, null where the key matched no row.
     * That slot-per-key shape is the lookup's contract: the caller reads output position {@code i}
     * as the answer for key {@code i}, and a miss has to occupy its position for that to hold.
     *
     * <p>Deliberately not the {@code scatterSingleByIdx} throw on a repeated idx. That helper
     * serves a {@code terminal.pk = parentInput.fk_value} join, where a second row per key is a
     * misconfiguration. A lookup joins on the author's declared key columns, which the schema
     * does not require to be unique, and the documented answer there is that one of the matching
     * rows is returned rather than that the request fails. First row wins, which under the
     * {@code idx} ordering is the first the database yielded for that key.
     */
    public static MethodSpec buildScatterLookupByIdxHelper() {
        TypeName resultRecord = ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), RECORD);
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        ClassName arrays = ClassName.get("java.util", "Arrays");
        return MethodSpec.methodBuilder("scatterLookupByIdx")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(resultRecord, "flat")
            .addParameter(int.class, "keyCount")
            .addCode(CodeBlock.builder()
                .addStatement("$T[] out = new $T[keyCount]", RECORD, RECORD)
                .beginControlFlow("for ($T r : flat)", RECORD)
                .addStatement("int idx = r.get($S, $T.class)", IDX_COLUMN, Integer.class)
                .beginControlFlow("if (out[idx] == null)")
                .addStatement("out[idx] = r")
                .endControlFlow()
                .endControlFlow()
                .addStatement("return $T.asList(out)", arrays)
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
     * windowed result by {@code __idx__} and hands each parent's bucket, with that parent's slot of
     * one {@code ConnectionHelper.BatchCounts} over the batch's count source, to the
     * {@code connection} function the rows method passes, which builds the connection class its
     * field's type names over the batch's shared {@code PageRequest}. Generic in that class, so
     * one helper serves every connection field of a fetchers class whatever type each returns.
     * Emitted once per fetcher class that has any connection-returning Split* field.
     *
     * <p>Each parent's bucket is 0..(pageSize+1) rows, the over-fetch-by-1 living per partition
     * in the windowed CTE, so the connection function trims it and reads the page flags off it.
     *
     * <p>{@code countSource} is the shared cursor-independent count derived table emitted by
     * the rows method. The one {@code BatchCounts} runs one {@code count(*)} grouped by
     * {@code __idx__} for the whole batch the first time any parent's {@code totalCount} is read
     * (same shape as the polymorphic batched path over its shared {@code pages} table).
     */
    public static MethodSpec buildScatterConnectionByIdxHelper(String outputPackage) {
        TypeName resultRecord = ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), RECORD);
        var connectionHelper = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage).connectionHelper();
        ClassName helperClass = ClassName.get(connectionHelper.packageName(), connectionHelper.simpleName());
        ClassName batchCountsClass = helperClass.nestedClass(
            no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator.BATCH_COUNTS_CLASS_NAME);
        ClassName totalCountClass = helperClass.nestedClass(
            no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator.TOTAL_COUNT_CLASS_NAME);
        TypeName tableWildcard = ParameterizedTypeName.get(TABLE, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        var connection = com.palantir.javapoet.TypeVariableName.get("C");
        TypeName listOfConnection = ParameterizedTypeName.get(LIST, connection);
        TypeName build = ParameterizedTypeName.get(ClassName.get("java.util.function", "BiFunction"),
            listOfRecord, totalCountClass, connection);
        return MethodSpec.methodBuilder("scatterConnectionByIdx")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addTypeVariable(connection)
            .returns(listOfConnection)
            .addParameter(resultRecord, "flat")
            .addParameter(int.class, "keyCount")
            .addParameter(tableWildcard, "countSource")
            .addParameter(build, "connection")
            .addCode(CodeBlock.builder()
                .addStatement("$T buckets = new $T<>(keyCount)", listOfListOfRecord, ARRAY_LIST)
                .beginControlFlow("for (int i = 0; i < keyCount; i++)")
                .addStatement("buckets.add(new $T<>())", ARRAY_LIST)
                .endControlFlow()
                .beginControlFlow("for ($T r : flat)", RECORD)
                .addStatement("int idx = r.get($S, $T.class)", IDX_COLUMN, Integer.class)
                .addStatement("buckets.get(idx).add(r)")
                .endControlFlow()
                .addStatement("$T counts = new $T(countSource, countSource.field($S, $T.class), keyCount)",
                    batchCountsClass, batchCountsClass, IDX_COLUMN, Integer.class)
                .addStatement("$T out = new $T<>(keyCount)", listOfConnection, ARRAY_LIST)
                .beginControlFlow("for (int i = 0; i < keyCount; i++)")
                .addStatement("out.add(connection.apply(buckets.get(i), counts.slot(i)))")
                .endControlFlow()
                .addStatement("return out")
                .build())
            .build();
    }

}
