package no.sikt.graphitron.rewrite.field;

/**
 * Represents the outcome of resolving one flat argument of a
 * {@link QueryField.LookupQueryField} against the return type's jOOQ table.
 *
 * <p>Only non-condition, non-orderBy arguments without a {@code TableInputType} are candidates
 * for flat-arg resolution. The sealed hierarchy distinguishes two states:
 *
 * <ul>
 *   <li>{@link ResolvedLookupArg} — the column was found in the table. Carries the Java field
 *       name and the jOOQ {@link org.jooq.Field} instance for use in code generation.</li>
 *   <li>{@link UnresolvedLookupArg} — the column name could not be matched to any field in the
 *       jOOQ table. The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports
 *       this as an error.</li>
 * </ul>
 */
public sealed interface LookupArgRef
        permits LookupArgRef.ResolvedLookupArg, LookupArgRef.UnresolvedLookupArg {

    /** The GraphQL argument name (also the key used in {@code env.getArguments()}). */
    String name();

    /** Whether the argument is a list type (determines row cardinality). */
    boolean list();

    /**
     * A flat lookup argument successfully resolved to a column in the return type's jOOQ table.
     *
     * <p>{@code javaColumnName} is the Java field name in the generated jOOQ table class
     * (e.g. {@code "CUSTOMER_ID"}).
     * {@code column} is the jOOQ {@link org.jooq.Field} instance for use in code generation
     * (type, name, etc.).
     */
    record ResolvedLookupArg(
        String name,
        boolean list,
        String javaColumnName,
        org.jooq.Field<?> column
    ) implements LookupArgRef {}

    /**
     * A flat lookup argument whose column name could not be matched to any field in the jOOQ
     * table.
     *
     * <p>{@code columnName} is the SQL column name that was attempted (from {@code @field(name:)}
     * or the GraphQL argument name).
     */
    record UnresolvedLookupArg(
        String name,
        boolean list,
        String columnName
    ) implements LookupArgRef {}
}
