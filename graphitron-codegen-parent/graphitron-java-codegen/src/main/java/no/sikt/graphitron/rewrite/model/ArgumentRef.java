package no.sikt.graphitron.rewrite.model;

/**
 * Represents one argument on a field, with its resolved state.
 *
 * <p>The builder classifies each argument into exactly one variant during schema building.
 * Arguments that cannot be classified cause the enclosing field to be classified as
 * {@link GraphitronField.UnclassifiedField} instead — no error-sentinel variants exist here.
 *
 * <p>The top-level split reflects purpose:
 * <ul>
 *   <li>{@link MethodParamArg} — argument passed through to the developer's Java method as-is.
 *       Graphitron does not generate SQL for these.
 *     <ul>
 *       <li>{@link MethodParamArg.ScalarParamArg} — scalar or enum, Java type determined by
 *           built-in scalar mapping.</li>
 *       <li>{@link MethodParamArg.ObjectParamArg} — developer-owned input object type (a
 *           {@link GraphitronType.InputType}). The backing Java class (record, POJO, Map, etc.)
 *           will be determined when input-type code generation is implemented.</li>
 *     </ul>
 *   </li>
 *   <li>{@link TableArg} — argument that Graphitron uses to generate SQL against the field's table.
 *     <ul>
 *       <li>{@link TableArg.ColumnFilterArg} — scalar resolved against a column; generates a
 *           {@code WHERE col = ?} condition.</li>
 *       <li>{@link TableArg.InputFilterArg} — table-bound input type (a
 *           {@link GraphitronType.TableInputType}); generates a complex WHERE filter.</li>
 *       <li>{@link TableArg.OrderByArg} — carries {@code @orderBy}; generates an ORDER BY clause.
 *           Invalid on lookup fields.</li>
 *       <li>{@link TableArg.FirstArg} — Relay {@code first}: forward pagination limit (LIMIT).</li>
 *       <li>{@link TableArg.LastArg} — Relay {@code last}: backward pagination limit (LIMIT).</li>
 *       <li>{@link TableArg.AfterArg} — Relay {@code after}: cursor for forward pagination
 *           (cursor-based WHERE).</li>
 *       <li>{@link TableArg.BeforeArg} — Relay {@code before}: cursor for backward pagination
 *           (cursor-based WHERE).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Common GraphQL argument metadata ({@code name}, {@code typeName}, {@code nonNull},
 * {@code list}) is available on all variants.
 */
public sealed interface ArgumentRef
        permits ArgumentRef.MethodParamArg, ArgumentRef.TableArg {

    String name();
    String typeName();
    boolean nonNull();
    boolean list();

    /**
     * Argument passed through to the developer's Java method as-is.
     * Graphitron does not generate SQL for these arguments.
     */
    sealed interface MethodParamArg extends ArgumentRef
            permits ArgumentRef.MethodParamArg.ScalarParamArg,
                    ArgumentRef.MethodParamArg.ObjectParamArg {

        /**
         * Scalar or enum argument passed directly as a Java method parameter.
         * The Java type is determined by the built-in scalar-to-Java mapping.
         */
        record ScalarParamArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements MethodParamArg {}

        /**
         * Developer-owned input object type passed as a Java method parameter.
         * The backing Java class (record, POJO, Map, etc.) will be determined
         * when input-type code generation is implemented.
         */
        record ObjectParamArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements MethodParamArg {}
    }

    /**
     * Argument that Graphitron uses to generate SQL against the field's table.
     */
    sealed interface TableArg extends ArgumentRef
            permits ArgumentRef.TableArg.ColumnFilterArg,
                    ArgumentRef.TableArg.InputFilterArg,
                    ArgumentRef.TableArg.OrderByArg,
                    ArgumentRef.TableArg.FirstArg,
                    ArgumentRef.TableArg.LastArg,
                    ArgumentRef.TableArg.AfterArg,
                    ArgumentRef.TableArg.BeforeArg {

        /**
         * Scalar argument resolved against a column on the return type's jOOQ table.
         * Generates a {@code WHERE col = ?} condition.
         *
         * <p>{@code javaColumnName} is the Java field name in the generated jOOQ table class
         * (e.g. {@code "FILM_ID"}). {@code columnClass} is the fully qualified Java class
         * name of the column type (e.g. {@code "java.lang.Long"}).
         */
        record ColumnFilterArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String javaColumnName,
            String columnClass
        ) implements TableArg {}

        /**
         * Table-bound input object type (a {@link GraphitronType.TableInputType}).
         * Graphitron generates a complex WHERE filter from the resolved input fields.
         *
         * <p>The actual {@link GraphitronType.TableInputType} instance is available via
         * {@link no.sikt.graphitron.rewrite.GraphitronSchema#types()}.
         */
        record InputFilterArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements TableArg {}

        /**
         * Argument carrying {@code @orderBy}. Generates an ORDER BY clause.
         * Invalid on lookup fields — the validator reports an error.
         *
         * <p>The input type must have exactly one enum field whose values carry {@code @order}
         * directives ({@code sortFieldName}) and exactly one direction enum field
         * ({@code directionFieldName}). If the structure is invalid the builder promotes the
         * enclosing field to {@link GraphitronField.UnclassifiedField}.
         */
        record OrderByArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String sortFieldName,
            String directionFieldName
        ) implements TableArg {}

        /**
         * Relay {@code first} argument: forward pagination limit.
         * Generates a {@code LIMIT} clause for forward traversal.
         */
        record FirstArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements TableArg {}

        /**
         * Relay {@code last} argument: backward pagination limit.
         * Generates a {@code LIMIT} clause for backward traversal.
         */
        record LastArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements TableArg {}

        /**
         * Relay {@code after} argument: forward pagination cursor.
         * Generates a cursor-based WHERE condition (seek) for forward traversal.
         */
        record AfterArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements TableArg {}

        /**
         * Relay {@code before} argument: backward pagination cursor.
         * Generates a cursor-based WHERE condition (seek) for backward traversal.
         */
        record BeforeArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list
        ) implements TableArg {}
    }
}
