package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
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

}
