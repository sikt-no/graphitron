package no.sikt.graphitron.rewrite.field;

/**
 * Represents one argument on a field, with its resolved state.
 *
 * <p>The builder classifies each argument into exactly one variant during schema building:
 *
 * <ul>
 *   <li>{@link InputTypeArg} — the argument type is a user-defined input type (including those
 *       flagged with {@code @orderBy} or {@code @condition}). The {@code orderBy} and
 *       {@code conditionArg} flags record those directives; the validator rejects them on lookup
 *       fields. Non-flagged input types feed the input-type code-generation path.</li>
 *   <li>{@link ColumnArg} — a scalar or list argument that is table-bound (i.e. column-bound).
 *       Two sub-variants track whether the column was successfully resolved:
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
     * <p>{@code orderBy} is {@code true} when the argument carries {@code @orderBy} — invalid on
     * lookup fields. {@code conditionArg} is {@code true} when the argument carries
     * {@code @condition} — also invalid on lookup fields. Both flags default to {@code false} for
     * ordinary input type arguments.
     */
    record InputTypeArg(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        boolean orderBy,
        boolean conditionArg
    ) implements ArgumentRef {}

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
