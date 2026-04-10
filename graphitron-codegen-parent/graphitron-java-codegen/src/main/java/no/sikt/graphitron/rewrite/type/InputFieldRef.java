package no.sikt.graphitron.rewrite.type;

/**
 * A field of a {@code @table}-annotated GraphQL input type, successfully resolved to a column
 * in the jOOQ table.
 *
 * <p>An {@code InputFieldRef} is only constructed when the column name can be matched in the jOOQ
 * table. When any field's column cannot be matched the containing input type is classified as
 * {@link GraphitronType.UnclassifiedType} at build time.
 *
 * <p>{@code name} is the GraphQL field name. {@code typeName} is the base GraphQL type name
 * (unwrapped). {@code nonNull} and {@code list} describe the GraphQL type wrapper.
 *
 * <p>{@code table} is the resolved jOOQ table the column belongs to.
 * {@code javaColumnName} is the Java field name in the jOOQ table class
 * (e.g. {@code "CUSTOMER_ID"}). {@code columnClass} is the fully qualified Java class name of
 * the column type (e.g. {@code "java.lang.Integer"}).
 */
public record InputFieldRef(
    String name,
    String typeName,
    boolean nonNull,
    boolean list,
    TableRef table,
    String javaColumnName,
    String columnClass
) {}
