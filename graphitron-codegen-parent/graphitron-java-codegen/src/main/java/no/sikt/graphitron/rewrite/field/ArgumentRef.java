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
 *           {@code @table} or by optimistic inference from the field's return type).</li>
 *       <li>{@link InputTypeArg.PlainInputTypeArg} — the type could not be resolved to a table,
 *           or carries {@code @orderBy}/{@code @condition} (both invalid on lookup fields).</li>
 *     </ul>
 *   </li>
 *   <li>{@link ColumnArg} — a scalar or list argument that is column-bound: column was
 *       successfully resolved against the return type's jOOQ table. Carries the Java field name
 *       and the jOOQ {@link org.jooq.Field} instance for code generation.</li>
 *   <li>{@link PlainScalarArg} — a scalar or list argument whose column could not be matched in
 *       the return type's jOOQ table. The validator reports this as an error.</li>
 * </ul>
 *
 * <p>Common GraphQL argument metadata ({@code name}, {@code typeName}, {@code nonNull},
 * {@code list}) is available on all variants.
 */
public sealed interface ArgumentRef
        permits ArgumentRef.InputTypeArg, ArgumentRef.ColumnArg, ArgumentRef.PlainScalarArg {

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
         * {@code @condition} — also invalid on lookup fields.
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
     * A scalar or list argument that is column-bound.
     *
     * <p>Column was successfully resolved against the return type's jOOQ table.
     * {@code javaColumnName} is the Java field name in the generated jOOQ table class
     * (e.g. {@code "CUSTOMER_ID"}). {@code column} is the jOOQ {@link org.jooq.Field} instance
     * for use in code generation.
     */
    record ColumnArg(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        String javaColumnName,
        org.jooq.Field<?> column
    ) implements ArgumentRef {}

    /**
     * A scalar or list argument whose column could not be matched in the return type's jOOQ table.
     *
     * <p>{@code columnName} is the SQL column name that was attempted (from
     * {@code @field(name:)} or the GraphQL argument name). The validator reports this as an error.
     */
    record PlainScalarArg(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        String columnName
    ) implements ArgumentRef {}
}
