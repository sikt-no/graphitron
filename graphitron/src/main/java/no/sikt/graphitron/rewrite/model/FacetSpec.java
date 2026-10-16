package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.grammar.FacetNaming;

/**
 * One {@code @asFacet}-marked filter-input field, resolved against the consuming
 * {@code @asConnection} carrier. Carries exactly what the facet emitter needs: which carrier
 * argument the binding rides in ({@code filterArgName}, half of the suppression identity: a
 * same-named field on a sibling input arg is a distinct binding), the authored name of the
 * column to {@code GROUP BY}, what GraphQL scalar the facet value has, whether that value is nullable, and
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
 *
 * <p>{@code columnName} is the authored {@code @field(name:)} value and nothing more: which column
 * on which table the counts group on is a catalog-resolved use-site fact, said by the planner's
 * {@code FacetPlan}. {@code referenceBound} is the definition-keyed fact that the field carries
 * {@code @reference}, so its column sits on the table the path ends at. The planner reads it to
 * refuse a reference facet that lost its own filter term (under {@code @condition(override: true)})
 * rather than grouping a same-named column on the carrier's table.
 */
public record FacetSpec(
    String filterArgName,
    String inputFieldName,
    String columnName,
    String valueTypeName,
    boolean valueNullable,
    String facetValueTypeName,
    boolean referenceBound
) {}
