package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLTypeUtil;

import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;

/**
 * The single home of the definition-keyed {@code @asFacet} well-formedness predicate (R13). The
 * synthesis walk ({@code ConnectionPromoter.facetSpecsFor}) includes a field iff
 * {@link #definitionKeyedRejection} returns {@code null}; the misuse reduction
 * ({@code GraphitronSchemaBuilder.facetMisuseReason}) surfaces the same non-null reason as the
 * build diagnostic. One predicate, two consumers, so the passes cannot drift (a facet the
 * promoter synthesises is by construction one the reduction accepts, and vice versa). The
 * use-keyed checks (reachability, override, carrier scope, duplicate labels) stay in the
 * reduction: they need the consuming-coordinate view the promoter's per-field walk does not have.
 */
final class FacetFieldValidation {

    private FacetFieldValidation() {
    }

    /**
     * The definition-keyed rejection reason for one {@code @asFacet} application, or {@code null}
     * when the binding is a well-formed direct-column facet (plain {@code @field}-bound, optional,
     * scalar/enum-valued, non-{@code ID}).
     */
    static String definitionKeyedRejection(GraphQLInputObjectField field) {
        if (field.hasAppliedDirective(DIR_REFERENCE)
                || field.hasAppliedDirective(DIR_CONDITION)
                || field.hasAppliedDirective(DIR_NODE_ID)) {
            return "@asFacet supports only direct-column bindings in v1; remove @reference / "
                + "@condition / @nodeId from the facet field or drop @asFacet (join-mediated and "
                + "node-ID facets are a follow-up)";
        }
        if (!field.hasAppliedDirective(DIR_FIELD)) {
            return "@asFacet requires @field(name:) naming the facet's column; the v1 facet "
                + "emitter only understands direct-column facet values";
        }
        if (field.getType() instanceof GraphQLNonNull) {
            return "@asFacet requires a nullable (optional) filter field; a non-null filter value "
                + "is always active, so its facet could never show the unfiltered pivot counts, "
                + "and the generated filter-minus-self fragments suppress a facet's own predicate "
                + "by leaving it unset. Drop the outer '!' from the field type";
        }
        var leaf = GraphQLTypeUtil.unwrapAll(field.getType());
        String unwrapped = leaf instanceof GraphQLNamedType named ? named.getName() : String.valueOf(leaf);
        if ("ID".equals(unwrapped)) {
            return "@asFacet on an ID field is not supported in v1; ID fields route through the "
                + "node-ID machinery, which the direct-column facet emitter cannot serve";
        }
        if (leaf instanceof GraphQLInputObjectType) {
            return "@asFacet must mark a scalar or enum field; '" + unwrapped + "' is an input "
                + "object (facet values are GROUP BY keys on one column)";
        }
        return null;
    }
}
