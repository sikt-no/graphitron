package no.sikt.graphitron.rewrite.generators.lookup;

/**
 * Per-field data for the {@code toInputRows} argument-mapping method.
 *
 * <p>{@code argName} is the GraphQL field name used as the key in the
 * {@code Map<String, Object>} provided by graphql-java at runtime (e.g. {@code "customerId"}).
 *
 * <p>{@code columnJavaName} is the Java field name in the jOOQ table class
 * (e.g. {@code "CUSTOMER_ID"}), used to reference {@code TABLE.COLUMN} in the generated record
 * construction.
 *
 * <p>{@code columnClass} is the fully qualified Java class name of the column type
 * (e.g. {@code "java.lang.Integer"}), used to cast the {@code Object} value retrieved from
 * the graphql-java argument map.
 */
public record LookupInputFieldSpec(
    String argName,
    String columnJavaName,
    String columnClass
) {}
