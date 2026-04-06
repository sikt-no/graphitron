package no.sikt.graphitron.rewrite.field;

/**
 * Represents one argument on a field, with its resolved state.
 *
 * <p>The builder classifies each argument into exactly one variant during schema building:
 *
 * <ul>
 *   <li>{@link InputTypeArg} — the argument type is a user-defined input type (sealed):
 *     <ul>
 *       <li>{@link InputTypeArg.TableInputTypeArg} — the type was resolved to a
 *           {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType} (either via
 *           {@code @table} or by optimistic inference from the field's return type). Code
 *           generation uses the input-type path for these.</li>
 *       <li>{@link InputTypeArg.PlainInputTypeArg} — the type could not be resolved to a table,
 *           or carries {@code @orderBy}/{@code @condition} (both invalid on lookup fields and
 *           reported as errors by the validator).</li>
 *     </ul>
 *   </li>
 *   <li>{@link ColumnArg} — a scalar or list argument that is column-bound (table-bound scalar,
 *       sealed):
 *     <ul>
 *       <li>{@link ColumnArg.ResolvedColumnArg} — column found; carries Java field name and jOOQ
 *           {@link org.jooq.Field} for code generation.</li>
 *       <li>{@link ColumnArg.UnresolvedColumnArg} — column not found; the validator reports an
 *           error.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Common GraphQL argument metadata ({@code name}, {@code typeName}, {@code nonNull},
 * {@code list}) is available on all variants.
 */
public sealed interface ArgumentRef
        permits ArgumentRef.InputTypeArg, ArgumentRef.ColumnArg {

    String name();
    String typeName();
    boolean nonNull();
    boolean list();

    /**
     * Argument whose type is a user-defined input type.
     *
     * <p>Two sub-variants encode whether the type was resolved to a table at schema-build time.
     */
    sealed interface InputTypeArg extends ArgumentRef
            permits ArgumentRef.InputTypeArg.TableInputTypeArg,
                    ArgumentRef.InputTypeArg.PlainInputTypeArg {

        /**
         * The type was resolved to a
         * {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType}.
         *
         * <p>Resolved either because the input type carries {@code @table}, or because the builder
         * inferred the table from the lookup field's return type. The actual
         * {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType} instance is
         * available via {@link no.sikt.graphitron.rewrite.GraphitronSchema#types()}.
         */
        record TableInputTypeArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements InputTypeArg {}

        /**
         * The type could not be resolved to a table, or carries {@code @orderBy} /
         * {@code @condition}.
         *
         * <p>{@code orderBy} is {@code true} when the argument carries {@code @orderBy} — invalid
         * on lookup fields. {@code conditionArg} is {@code true} when the argument carries
         * {@code @condition} — also invalid on lookup fields. When both flags are {@code false},
         * the type is an ordinary input type whose table could not be inferred; the validator
         * reports an error if code generation requires a table.
         */
        record PlainInputTypeArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            boolean orderBy,
            boolean conditionArg
        ) implements InputTypeArg {}
    }

    /**
     * A scalar or list argument that is column-bound (table-bound scalar).
     *
     * <p>The two sub-variants differ by whether the database column was resolved at schema-build
     * time.
     */
    sealed interface ColumnArg extends ArgumentRef
            permits ArgumentRef.ColumnArg.ResolvedColumnArg, ArgumentRef.ColumnArg.UnresolvedColumnArg {

        /**
         * Column was successfully resolved against the return type's jOOQ table.
         *
         * <p>{@code javaColumnName} is the Java field name in the generated jOOQ table class
         * (e.g. {@code "CUSTOMER_ID"}).
         * {@code column} is the jOOQ {@link org.jooq.Field} instance for use in code generation.
         */
        record ResolvedColumnArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String javaColumnName,
            org.jooq.Field<?> column
        ) implements ColumnArg {}

        /**
         * Column could not be matched in the return type's jOOQ table.
         *
         * <p>{@code columnName} is the SQL column name that was attempted (from
         * {@code @field(name:)} or the GraphQL argument name). The validator reports this as an
         * error.
         */
        record UnresolvedColumnArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String columnName
        ) implements ColumnArg {}
    }
}
