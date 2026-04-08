package no.sikt.graphitron.rewrite.generators.lookup;

/**
 * Per-column data for the {@code toInputRows} argument-mapping method.
 *
 * <p>{@code argName} is either:
 * <ul>
 *   <li>the field name within the per-element {@code Map<String,Object>} when this spec belongs to
 *       a field with a {@code TableInputType} argument (e.g. {@code "customerId"}); or</li>
 *   <li>the top-level GraphQL argument name when this spec is a direct (flat) argument
 *       (e.g. {@code "tenantId"} or {@code "ids"}).</li>
 * </ul>
 *
 * <p>{@code columnClass} is the fully qualified Java class name of the column type
 * (e.g. {@code "java.lang.Integer"}), used to cast values retrieved from the argument map.
 *
 * <p>{@code list} is {@code true} when this is a direct list argument (e.g. {@code ids: [ID]}).
 * Only meaningful when {@link LookupSpec#inputArgName()} is {@code null} (flat-args case).
 * In the flat-args case, list arguments determine row cardinality and have a local variable
 * declared before the {@code IntStream}; scalar arguments ({@code list = false}) are read from
 * the {@code arguments} map directly in every row.
 */
public record LookupInputFieldSpec(
    String argName,
    String columnClass,
    boolean list
) {}
