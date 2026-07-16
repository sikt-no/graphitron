package no.sikt.graphitron.rewrite.model;

/**
 * Single source of truth for the names of the synthesised facet object types. Shared by the
 * synthesis pass ({@code ConnectionPromoter}) and every classifier/validator site that must refer
 * to a facet type by name, so the two can never drift.
 */
public final class FacetNaming {

    private FacetNaming() {
    }

    /**
     * The name of the reusable {@code *FacetValue} object type for a facet whose filter-input
     * element type is {@code scalar} (e.g. {@code MpaaRating}, {@code String}) with the given
     * element nullability. Keyed on <em>both</em> so a non-null and a nullable facet over the same
     * scalar get distinct types ({@code MpaaRatingFacetValue} vs {@code MpaaRatingFacetValueOrNull})
     * instead of colliding on one {@code value} nullability.
     */
    public static String facetValueTypeName(String scalar, boolean nullable) {
        return nullable ? scalar + "FacetValueOrNull" : scalar + "FacetValue";
    }

    /** The name of the per-connection facets container type, e.g. {@code QueryFilmerConnectionFacets}. */
    public static String facetsTypeName(String connectionName) {
        return connectionName + "Facets";
    }
}
