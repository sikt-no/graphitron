package no.sikt.graphitron.rewrite.field;

/**
 * Represents one argument on a {@link QueryField.LookupQueryField}, with its resolved state.
 *
 * <p>The builder classifies each argument into exactly one variant during schema building:
 * <ul>
 *   <li>{@link OrderByArg} — argument carries {@code @orderBy}; rejected by the validator.</li>
 *   <li>{@link ConditionArg} — argument carries {@code @condition}; rejected by the validator.</li>
 *   <li>{@link InputTypeArg} — the argument type is a user-defined input type (either already a
 *       {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType} or an
 *       {@link no.sikt.graphitron.rewrite.type.GraphitronType.InputType} that will be promoted).
 *       Code generation uses the input-type path for these.</li>
 *   <li>{@link ResolvedFlatArg} — a scalar or list argument whose database column was found in
 *       the return type's jOOQ table. Carries the Java field name and jOOQ {@link org.jooq.Field}
 *       for code generation.</li>
 *   <li>{@link UnresolvedFlatArg} — a scalar or list argument whose column could not be matched.
 *       The validator reports this as an error.</li>
 * </ul>
 *
 * <p>Common GraphQL argument metadata ({@code name}, {@code typeName}, {@code nonNull},
 * {@code list}) is available on all variants.
 */
public sealed interface LookupArgRef
        permits LookupArgRef.OrderByArg, LookupArgRef.ConditionArg, LookupArgRef.InputTypeArg,
                LookupArgRef.ResolvedFlatArg, LookupArgRef.UnresolvedFlatArg {

    String name();
    String typeName();
    boolean nonNull();
    boolean list();

    /** Argument annotated with {@code @orderBy} — invalid on a lookup field. */
    record OrderByArg(
        String name, String typeName, boolean nonNull, boolean list
    ) implements LookupArgRef {}

    /** Argument annotated with {@code @condition} — invalid on a lookup field. */
    record ConditionArg(
        String name, String typeName, boolean nonNull, boolean list
    ) implements LookupArgRef {}

    /**
     * Argument whose type is a user-defined input type. Handled via the input-type code path in
     * {@link no.sikt.graphitron.rewrite.generators.lookup.LookupSpecBuilder}.
     */
    record InputTypeArg(
        String name, String typeName, boolean nonNull, boolean list
    ) implements LookupArgRef {}

    /**
     * A scalar or list argument whose database column was successfully resolved against the
     * return type's jOOQ table.
     *
     * <p>{@code javaColumnName} is the Java field name in the generated jOOQ table class
     * (e.g. {@code "CUSTOMER_ID"}).
     * {@code column} is the jOOQ {@link org.jooq.Field} instance for use in code generation.
     */
    record ResolvedFlatArg(
        String name, String typeName, boolean nonNull, boolean list,
        String javaColumnName,
        org.jooq.Field<?> column
    ) implements LookupArgRef {}

    /**
     * A scalar or list argument whose column could not be matched in the return type's jOOQ table.
     *
     * <p>{@code columnName} is the SQL column name that was attempted (from {@code @field(name:)}
     * or the GraphQL argument name). The validator reports this as an error.
     */
    record UnresolvedFlatArg(
        String name, String typeName, boolean nonNull, boolean list,
        String columnName
    ) implements LookupArgRef {}
}
