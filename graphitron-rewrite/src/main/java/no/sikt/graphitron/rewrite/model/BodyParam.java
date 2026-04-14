package no.sikt.graphitron.rewrite.model;

/**
 * One parameter of a generated condition method, as seen from the method body generator.
 *
 * <p>Carries everything {@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator}
 * needs to emit both the method signature and the method body for one filter argument:
 *
 * <ul>
 *   <li>{@link #name()} — the parameter name (matches the GraphQL argument name).</li>
 *   <li>{@link #column()} — the resolved jOOQ column to filter on.</li>
 *   <li>{@link #javaType()} — the fully qualified Java type for the method parameter
 *       (e.g. {@code "java.lang.String"} for scalars, the enum class name for
 *       {@link CallSiteExtraction.EnumValueOf}).</li>
 *   <li>{@link #nonNull()} — when {@code true}, the null guard is omitted and the condition
 *       is always applied; when {@code false}, the condition is wrapped in a null check.</li>
 *   <li>{@link #extraction()} — carried here so that
 *       {@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator} can generate the
 *       static enum-map field for {@link CallSiteExtraction.TextMapLookup} params.</li>
 *   <li>{@link #graphqlTypeName()} — the GraphQL scalar type name (e.g. {@code "ID"},
 *       {@code "Int"}, {@code "String"}). Used by fetcher generators that build inline
 *       conditions: {@code ID} arguments are delivered as {@code String} by GraphQL-Java
 *       and require jOOQ's {@code DataType.convert()} to coerce to the column type; all
 *       other scalars are delivered as their natural Java type and need no conversion.</li>
 * </ul>
 *
 * <p>The parallel call-site view of this parameter is {@link CallParam}. A {@link BodyParam}
 * and its corresponding {@link CallParam} share the same {@code name} and {@code extraction}.
 */
public record BodyParam(
    String name,
    ColumnRef column,
    String javaType,
    boolean nonNull,
    boolean list,
    CallSiteExtraction extraction,
    String graphqlTypeName
) {}
