package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.LookupField;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.QueryField;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.ENV;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.LIST;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.SELECTED_FIELD;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.toCamelCase;

/**
 * Emits the VALUES + JOIN lookup select for a {@link LookupField}, driven by its
 * {@link LookupMapping}.
 *
 * <p>Each lookup field gets two generated artifacts in its {@code *Fetchers} class:
 * <ol>
 *   <li>A pure helper {@code <fieldName>InputRows(env, table) -> Row[]} that extracts each
 *       {@code @lookupKey} arg from the {@code DataFetchingEnvironment} and builds a typed
 *       {@code Row} per input index using {@code DSL.val(value, column.getDataType())}. jOOQ
 *       applies the target column's Converter internally; no SQL-level {@code CAST} is
 *       rendered. Pure function of {@code env} + {@code table} — unit-testable in isolation.</li>
 *   <li>The fetcher body that calls the helper, short-circuits on empty input, builds a
 *       {@code DSL.values(rows).as("<fieldName>Input", "idx", "COL1", …)} derived table,
 *       joins it via {@code USING (COL1, COL2, …)}, and orders by the derived table's
 *       {@code idx} column to preserve input ordering.</li>
 * </ol>
 *
 * <p>USING-join works by construction because the VALUES column labels are emitted to match
 * the target column names ({@code LookupColumn.targetColumn().javaName()}); the {@code idx}
 * ordering column is naturally excluded from USING because it is not on the target table.
 *
 * <p>See {@code docs/argument-resolution.md} for the Phase 1 design rationale.
 */
final class LookupValuesJoinEmitter {

    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");
    private static final ClassName ROW_N = ClassName.get("org.jooq", "RowN");
    private static final TypeName WILDCARD_TABLE =
        ParameterizedTypeName.get(TABLE, WildcardTypeName.subtypeOf(Object.class));
    // RowN[] — RowN is the untyped row interface used for dynamic-arity VALUES tables.
    // Row[] (raw Row) cannot be passed to DSL.values(...) because the Row2/Row3/... overloads
    // are more specific than the RowN... fallback and raw Row matches none of them.
    private static final ArrayTypeName ROW_ARRAY = ArrayTypeName.of(ROW_N);

    private LookupValuesJoinEmitter() {}

    /** Returns the GraphQL field name for a {@link LookupField}, used to derive helper names. */
    static String fieldName(LookupField field) {
        return switch (field) {
            case QueryField.QueryLookupTableField f -> f.name();
            case ChildField.LookupTableField f -> f.name();
            case ChildField.SplitLookupTableField f -> f.name();
            case ChildField.RecordLookupTableField f -> f.name();
        };
    }

    /** The name of the generated input-rows helper method for a lookup field. */
    static String inputRowsMethodName(LookupField field) {
        return fieldName(field) + "InputRows";
    }

    /** The VALUES-table alias used in the fetcher body. */
    static String inputTableAlias(LookupField field) {
        return fieldName(field) + "Input";
    }

    /**
     * Generates the {@code <fieldName>InputRows(DataFetchingEnvironment env, <TargetTable> table) -> Row[]}
     * helper method. The helper:
     * <ol>
     *   <li>Extracts each {@code @lookupKey} arg from {@code env}.</li>
     *   <li>Computes row count {@code n} — the length of the first list-typed argument, broadcasting
     *       scalars across all rows. With no list arg, {@code n = 1}.</li>
     *   <li>Returns {@code new Row[0]} when the list arg is {@code null} or empty (short-circuit).</li>
     *   <li>Builds one {@code DSL.row(DSL.inline(i), DSL.val(v, table.COL.getDataType()), …)} per
     *       index. {@code DSL.val} invokes the target column's Converter on the raw value — no
     *       Java-side {@code .convert()} call and no SQL {@code CAST}.</li>
     * </ol>
     *
     * @param field the lookup field (source of {@link LookupMapping})
     * @param targetTableClass the JavaPoet reference to the concrete jOOQ table class (e.g. {@code Film})
     */
    static MethodSpec buildInputRowsMethod(LookupField field, ClassName targetTableClass) {
        List<LookupMapping.LookupColumn> columns = requireColumns(field);

        var builder = MethodSpec.methodBuilder(inputRowsMethodName(field))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ROW_ARRAY)
            .addParameter(ENV, "env")
            .addParameter(targetTableClass, "table");

        // Extract each arg into a local. Lists are List<?> (nullable); scalars are Object.
        // env.getArgument is <T>-inferred so the cast is implicit.
        for (var col : columns) {
            String local = argLocalName(col);
            if (col.list()) {
                builder.addStatement("$T<?> $L = env.getArgument($S)", LIST, local, col.argName());
            } else {
                builder.addStatement("$T $L = env.getArgument($S)", Object.class, local, col.argName());
            }
        }

        addRowBuildingCore(builder, columns);
        return builder.build();
    }

    /**
     * Child-field variant of {@link #buildInputRowsMethod}. Reads {@code @lookupKey} args from a
     * {@link graphql.schema.SelectedField} instead of a {@code DataFetchingEnvironment}, since
     * the args live on the child selection when the lookup is projected inline by a parent's
     * {@code $fields} (argres Phase 2a). Row-construction core is shared with the root variant.
     *
     * <p>Signature: {@code <fieldName>InputRows(SelectedField sf, <TargetTable> table) -> RowN[]}.
     *
     * <p>{@code SelectedField.getArguments()} returns {@code Map<String, Object>}; list args need
     * an explicit {@code (List<?>)} cast to match the typed-local declaration the shared core
     * expects.
     */
    static MethodSpec buildChildInputRowsMethod(LookupField field, ClassName targetTableClass) {
        List<LookupMapping.LookupColumn> columns = requireColumns(field);

        var builder = MethodSpec.methodBuilder(inputRowsMethodName(field))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ROW_ARRAY)
            .addParameter(SELECTED_FIELD, "sf")
            .addParameter(targetTableClass, "table");

        // Extract each arg. sf.getArguments() is Map<String,Object>; list args take an explicit cast.
        for (var col : columns) {
            String local = argLocalName(col);
            if (col.list()) {
                builder.addStatement("$T<?> $L = ($T<?>) sf.getArguments().get($S)",
                    LIST, local, LIST, col.argName());
            } else {
                builder.addStatement("$T $L = sf.getArguments().get($S)", Object.class, local, col.argName());
            }
        }

        addRowBuildingCore(builder, columns);
        return builder.build();
    }

    /** Classifier-invariant check shared by the root and child input-rows builders. */
    private static List<LookupMapping.LookupColumn> requireColumns(LookupField field) {
        List<LookupMapping.LookupColumn> columns = field.lookupMapping().columns();
        if (columns.isEmpty()) {
            // projectForFilter enforces non-empty LookupMapping before classification; reaching this
            // is a generator-side bug, not a schema error.
            throw new IllegalStateException(
                "LookupField '" + fieldName(field) + "' has no lookup columns; classifier invariant violated");
        }
        return columns;
    }

    /**
     * Shared row-building tail: row-count computation, empty short-circuit, typed-value loop,
     * return. Assumes each column's value is already in a local named via {@link #argLocalName}
     * and that {@code table} refers to the target-table alias.
     */
    private static void addRowBuildingCore(MethodSpec.Builder builder, List<LookupMapping.LookupColumn> columns) {
        // Row count N — from the first list column's length, or 1 if all scalar.
        var primaryList = columns.stream().filter(LookupMapping.LookupColumn::list).findFirst().orElse(null);
        if (primaryList == null) {
            builder.addStatement("int n = 1");
        } else {
            String local = argLocalName(primaryList);
            builder.addStatement("int n = $L == null ? 0 : $L.size()", local, local);
        }

        // Short-circuit empty input — jOOQ rejects empty RowN[], and legacy in([]) → no rows.
        builder.addCode("if (n == 0) return new $T[0];\n", ROW_N);

        // Build typed rows. Cells are declared as Object[] and passed to DSL.row(Object...) so the
        // call resolves to the RowN-producing overload (a varargs Field<?>[] call would bind to
        // the more-specific RowN<T1, T2, …> overloads instead, which we cannot name dynamically).
        builder.addStatement("$T rows = new $T[n]", ROW_ARRAY, ROW_N);
        builder.beginControlFlow("for (int i = 0; i < n; i++)");

        var cells = CodeBlock.builder();
        cells.add("$T.inline(i)", DSL);
        for (var col : columns) {
            cells.add(", ");
            String valueExpr = col.list()
                ? argLocalName(col) + ".get(i)"
                : argLocalName(col);
            // DSL.val(value, dataType) — typed bind; jOOQ's Convert + the column's registered
            // Converter coerce the raw env value (String / Integer / enum instance / …) to the
            // column's Java type at bind time. No SQL CAST rendered.
            cells.add("$T.val($L, table.$L.getDataType())",
                DSL, valueExpr, col.targetColumn().javaName());
        }
        builder.addStatement("$T[] cells = new $T[] { $L }", Object.class, Object.class, cells.build());
        builder.addStatement("rows[i] = $T.row(cells)", DSL);
        builder.endControlFlow();
        builder.addStatement("return rows");
    }

    /**
     * Generates the VALUES + JOIN derived-table select body for a lookup field's rows method.
     *
     * <p>Expects two locals already declared in the surrounding method:
     * <ul>
     *   <li>{@code table} — the target jOOQ table alias (from {@link GeneratorUtils#declareTableLocal}).</li>
     *   <li>{@code dsl} — the {@code DSLContext} (declared by the caller after this block's emitted
     *       rows-array declaration, because the empty-input short-circuit uses it).</li>
     * </ul>
     *
     * <p>Emits:
     * <pre>{@code
     * Row[] rows = <fieldName>InputRows(env, table);
     * var dsl = graphitronContext(env).getDslContext(env);
     * if (rows.length == 0) return dsl.newResult();
     * Table<?> input = DSL.values(rows).as("<fieldName>Input", "idx", "COL1", "COL2", …);
     * return dsl.select(<typeFieldsCall>)
     *           .from(table)
     *           .join(input).using(table.COL1, table.COL2, …)
     *           .where(condition)
     *           .orderBy(input.field("idx"))
     *           .fetch();
     * }</pre>
     *
     * <p>The {@code .where(condition)} clause expects the caller to have declared a
     * {@code Condition condition} local before this block. Callers typically initialise it with
     * {@code DSL.noCondition()} and AND in any non-key filters (field-level {@code @condition},
     * per-arg {@code @condition}); when there are no such filters, the {@code .where(noCondition())}
     * is a no-op that jOOQ optimises away.
     *
     * @param field                the lookup field
     * @param typeFieldsCallStatic the JavaPoet expression for {@code <TypeName>.$fields(env.getSelectionSet(), table, env)}.
     */
    static CodeBlock buildFetcherBody(LookupField field, CodeBlock typeFieldsCall) {
        List<LookupMapping.LookupColumn> columns = field.lookupMapping().columns();
        String alias = inputTableAlias(field);

        // VALUES column labels — "idx", then one per lookup column. Labels must match the target
        // column's SQL name (e.g. "film_id"), not the jOOQ Java field name (e.g. "FILM_ID"), because
        // Postgres treats quoted identifiers case-sensitively and USING compares the rendered names.
        var aliasArgs = CodeBlock.builder();
        aliasArgs.add("$S, $S", alias, "idx");
        for (var col : columns) {
            aliasArgs.add(", $S", col.targetColumn().sqlName());
        }

        // USING column arguments — references to target-table field constants.
        var usingArgs = CodeBlock.builder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) usingArgs.add(", ");
            usingArgs.add("table.$L", columns.get(i).targetColumn().javaName());
        }

        return CodeBlock.builder()
            .addStatement("$T rows = $L(env, table)", ROW_ARRAY, inputRowsMethodName(field))
            .addStatement("var dsl = graphitronContext(env).getDslContext(env)")
            .add("if (rows.length == 0) return dsl.newResult();\n")
            .addStatement("$T input = $T.values(rows).as($L)", WILDCARD_TABLE, DSL, aliasArgs.build())
            .add("return dsl\n")
            .indent()
            .add(".select($L)\n", typeFieldsCall)
            .add(".from(table)\n")
            .add(".join(input).using($L)\n", usingArgs.build())
            .add(".where(condition)\n")
            .add(".orderBy(input.field($S))\n", "idx")
            .add(".fetch();\n")
            .unindent()
            .build();
    }

    /**
     * Returns the Java local-variable name for a lookup column's extracted value. Converts
     * {@code snake_case} to {@code lowerCamelCase} and suffixes list-typed args with {@code Keys}.
     */
    private static String argLocalName(LookupMapping.LookupColumn col) {
        String camel = toCamelCase(col.argName());
        return col.list() ? camel + "Keys" : camel;
    }

}
