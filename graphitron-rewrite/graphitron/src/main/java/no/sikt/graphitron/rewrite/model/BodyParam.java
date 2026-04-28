package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * One parameter of a generated condition method, as seen from the method body generator.
 *
 * <p>Carries everything {@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator}
 * needs to emit both the method signature and the method body for one filter argument:
 *
 * <ul>
 *   <li>{@link #name()} — the parameter name (matches the GraphQL argument name).</li>
 *   <li>{@link #javaType()} — the fully qualified Java type for the method parameter
 *       (e.g. {@code "java.lang.String"} for scalars, the enum class name for
 *       {@link CallSiteExtraction.EnumValueOf}).</li>
 *   <li>{@link #nonNull()} — when {@code true}, the null guard is omitted and the condition
 *       is always applied; when {@code false}, the condition is wrapped in a null check.</li>
 *   <li>{@link #list()} — whether the parameter is a list (drives the call-site extraction
 *       shape too).</li>
 *   <li>{@link #extraction()} — how to extract the value at the fetcher call site; also
 *       carried here so that
 *       {@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator} can generate the
 *       static enum-map field for {@link CallSiteExtraction.TextMapLookup} params. Use
 *       {@link CallSiteExtraction.JooqConvert} for {@code ID} arguments that require jOOQ's
 *       {@code DataType.convert()} coercion at the call site.</li>
 * </ul>
 *
 * <p>The parallel call-site view of this parameter is {@link CallParam}. A {@link BodyParam}
 * and its corresponding {@link CallParam} share the same {@code name} and {@code extraction}.
 */
public sealed interface BodyParam permits BodyParam.ColumnEq, BodyParam.NodeIdIn {

    /** Parameter name (matches the GraphQL input field name). */
    String name();

    /** Whether the parameter is a list (drives the call-site extraction shape too). */
    boolean list();

    /** Whether a runtime null guard is needed. */
    boolean nonNull();

    /** Java type of the method parameter (e.g. {@code "java.lang.String"}). */
    String javaType();

    /** How to extract the value at the fetcher call site (NestedInputField for input-type fields). */
    CallSiteExtraction extraction();

    /**
     * Single-column equality / IN predicate. Body emits {@code table.col.eq(arg)} (scalar) or
     * {@code table.col.in(arg)} (list).
     */
    record ColumnEq(
        String name,
        ColumnRef column,
        String javaType,
        boolean nonNull,
        boolean list,
        CallSiteExtraction extraction
    ) implements BodyParam {}

    /**
     * A {@code [ID!]} list of base64 node IDs that translates to a composite-PK IN predicate.
     * Body emits
     * {@code NodeIdEncoder.hasIds("typeId", arg, table.col1, ..., table.colN)}.
     * {@code javaType} is always {@code "java.lang.String"} and {@code list} is always
     * {@code true}.
     */
    record NodeIdIn(
        String name,
        String nodeTypeId,
        List<ColumnRef> nodeKeyColumns,
        boolean nonNull,
        CallSiteExtraction extraction
    ) implements BodyParam {
        @Override public String javaType() { return String.class.getName(); }
        @Override public boolean list() { return true; }
    }
}
