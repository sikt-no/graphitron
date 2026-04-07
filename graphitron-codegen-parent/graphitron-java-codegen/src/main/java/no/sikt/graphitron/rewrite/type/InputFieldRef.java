package no.sikt.graphitron.rewrite.type;

/**
 * Represents the outcome of resolving one field of a {@code @table}-annotated GraphQL input type
 * against the jOOQ catalog.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link TableInputField} — the field was successfully resolved to a column in a jOOQ table;
 *       carries the table, Java column field name, and the jOOQ {@link org.jooq.Field} instance.</li>
 *   <li>{@link UnresolvedInputField} — the column name could not be matched to any field in the
 *       jOOQ table. The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this
 *       as an error.</li>
 * </ul>
 *
 * <p>Common GraphQL type information ({@code name}, {@code typeName}, {@code nonNull}, {@code list})
 * is available on both variants.
 */
public sealed interface InputFieldRef
        permits InputFieldRef.TableInputField, InputFieldRef.UnresolvedInputField {

    /** The GraphQL field name. */
    String name();

    /** The base GraphQL type name (unwrapped). */
    String typeName();

    boolean nonNull();
    boolean list();

    /**
     * A field successfully resolved to a column in a jOOQ table.
     *
     * <p>{@code table} is the resolved jOOQ table wrapper (always a
     * {@link TableRef.ResolvedTable} in practice, since unresolved tables produce
     * {@link UnresolvedInputField} for all their fields).
     * {@code javaColumnName} is the Java field name in the jOOQ table class (e.g. {@code "CUSTOMER_ID"}).
     * {@code columnClass} is the fully qualified Java class name of the column type
     * (e.g. {@code "java.lang.Integer"}).
     */
    record TableInputField(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        TableRef.ResolvedTable table,
        String javaColumnName,
        String columnClass
    ) implements InputFieldRef {}

    /**
     * A field whose column name could not be matched to any field in the jOOQ table.
     *
     * <p>{@code columnName} is the SQL column name (from {@code @field(name:)} or the GraphQL field
     * name) that failed to resolve.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this as an error.
     */
    record UnresolvedInputField(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        String columnName
    ) implements InputFieldRef {}
}
