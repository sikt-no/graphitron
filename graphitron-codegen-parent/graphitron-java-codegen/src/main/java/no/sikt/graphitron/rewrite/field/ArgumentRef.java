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
 *       <li>{@link InputTypeArg.OrderByArg} — the argument carries {@code @orderBy}; its input
 *           type must have a specific structure (one enum with {@code @order} values, one
 *           direction field). Invalid on lookup fields.</li>
 *       <li>{@link InputTypeArg.PlainInputTypeArg} — the type could not be resolved to a table
 *           and carries no recognised directive.</li>
 *     </ul>
 *   </li>
 *   <li>{@link ScalarArg} — the argument type is a scalar or enum (sealed):
 *     <ul>
 *       <li>{@link ScalarArg.ColumnArg} — resolved against the return type's jOOQ table; carries
 *           the Java field name and the jOOQ {@link org.jooq.Field} instance.</li>
 *       <li>{@link ScalarArg.UnboundScalarArg} — column could not be matched; the validator
 *           reports an error.</li>
 *       <li>{@link ScalarArg.ParamArg} — scalar passed directly as a Java parameter without
 *           column binding (e.g. a context argument or method parameter).</li>
 *     </ul>
 *   </li>
 *   <li>{@link UnclassifiedArg} — the argument carries a directive that is not supported at the
 *       argument level (e.g. {@code @condition}). The validator reports an error.</li>
 * </ul>
 *
 * <p>Common GraphQL argument metadata ({@code name}, {@code typeName}, {@code nonNull},
 * {@code list}) is available on all variants.
 */
public sealed interface ArgumentRef
        permits ArgumentRef.InputTypeArg, ArgumentRef.ScalarArg, ArgumentRef.UnclassifiedArg {

    String name();
    String typeName();
    boolean nonNull();
    boolean list();

    /**
     * Argument whose type is a user-defined input type.
     *
     * <p>Three sub-variants encode how the type was classified at schema-build time.
     */
    sealed interface InputTypeArg extends ArgumentRef
            permits ArgumentRef.InputTypeArg.TableInputTypeArg,
                    ArgumentRef.InputTypeArg.OrderByArg,
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
         * The argument carries {@code @orderBy} and its input type has been resolved to the
         * required structure.
         *
         * <p>The input type must have exactly one enum field whose values carry {@code @order}
         * directives ({@code sortFieldName}) and exactly one direction enum field ({@code directionFieldName}).
         * Valid on {@link no.sikt.graphitron.rewrite.field.QueryField.TableQueryField}; the
         * validator reports an error on lookup fields.
         *
         * <p>If the input type cannot be resolved to this structure (type not found, wrong number
         * of sort/direction fields), the builder produces an {@link ArgumentRef.UnclassifiedArg}
         * instead.
         */
        record OrderByArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String sortFieldName,
            String directionFieldName
        ) implements InputTypeArg {}

        /**
         * The type could not be resolved to a table and carries no recognised directive.
         */
        record PlainInputTypeArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements InputTypeArg {}
    }

    /**
     * Argument whose type is a scalar or enum.
     *
     * <p>Three sub-variants cover the resolution states for scalar arguments.
     */
    sealed interface ScalarArg extends ArgumentRef
            permits ArgumentRef.ScalarArg.ColumnArg,
                    ArgumentRef.ScalarArg.UnboundScalarArg,
                    ArgumentRef.ScalarArg.ParamArg {

        /**
         * Scalar argument resolved against the return type's jOOQ table.
         *
         * <p>{@code javaColumnName} is the Java field name in the generated jOOQ table class
         * (e.g. {@code "CUSTOMER_ID"}). {@code columnClass} is the fully qualified Java class
         * name of the column type (e.g. {@code "java.lang.Long"}).
         */
        record ColumnArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String javaColumnName,
            String columnClass
        ) implements ScalarArg {}

        /**
         * Scalar argument whose column could not be matched in the return type's jOOQ table.
         *
         * <p>{@code columnName} is the SQL column name that was attempted (from
         * {@code @field(name:)} or the GraphQL argument name). The validator reports this as an
         * error when column binding is required.
         */
        record UnboundScalarArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String columnName
        ) implements ScalarArg {}

        /**
         * Scalar argument passed directly as a Java parameter without column binding.
         *
         * <p>Used for context arguments, method parameters, and other scalars that are forwarded
         * to a Java method rather than mapped to a database column.
         */
        record ParamArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements ScalarArg {}
    }

    /**
     * Argument that carries a directive not supported at the argument level.
     *
     * <p>Example: {@code @condition} is only valid on {@code FIELD_DEFINITION}; using it on an
     * argument produces this variant. The validator reports {@code reason} as an error.
     */
    record UnclassifiedArg(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        String reason
    ) implements ArgumentRef {}
}
