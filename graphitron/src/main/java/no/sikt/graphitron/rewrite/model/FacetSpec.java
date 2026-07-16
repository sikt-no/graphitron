package no.sikt.graphitron.rewrite.model;

/**
 * One {@code @asFacet}-marked filter-input field, resolved against the consuming
 * {@code @asConnection} carrier. Carries exactly what the facet emitter needs: which carrier
 * argument the binding rides in ({@code filterArgName}, half of the suppression identity: a
 * same-named field on a sibling input arg is a distinct binding), which column to
 * {@code GROUP BY}, what GraphQL scalar the facet value has, whether that value is nullable, and
 * which synthesised {@code *FacetValue} object type the counts surface through.
 *
 * <p>The list of these rides {@link GraphitronType.ConnectionType#facets()} as a contained
 * denormalized view: the {@code @asFacet} fact is authored at the filter input type's member
 * coordinate, and this record is its use-site resolution against the carrier's table.
 *
 * <p>{@code valueNullable} mirrors the annotated filter field's list-element nullability. It
 * drives both the {@code *FacetValue} type name (via
 * {@link FacetNaming#facetValueTypeName(String, boolean)}) and the per-arm SQL scrub: a non-null
 * value appends {@code AND <col> IS NOT NULL} so a {@code GROUP BY} NULL key can never reach a
 * non-null output field.
 */
public record FacetSpec(
    String filterArgName,
    String inputFieldName,
    String columnName,
    String valueTypeName,
    boolean valueNullable,
    String facetValueTypeName
) {}
