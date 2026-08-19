package no.sikt.graphitron.rewrite.model;

import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

/**
 * A {@link GraphitronType} variant that carries its own graphql-java {@link GraphQLObjectType}
 * form instead of leaving the assembled schema as the place to look it up.
 *
 * <p>Two populations opt in. The connection-promotion arms
 * ({@link GraphitronType.ConnectionType}, {@link GraphitronType.EdgeType},
 * {@link GraphitronType.PageInfoType}, {@link GraphitronType.FacetsType},
 * {@link GraphitronType.FacetValueType}) carry a form the promoter mints programmatically on the
 * directive-driven arm and references from the assembled schema on the structural arm.
 * {@link GraphitronType.NestingType} carries the assembled-schema object it was classified from at
 * its embedding edge.
 *
 * <p>Read via {@code instanceof CarriesObjectForm}, following the {@link EmitsPerTypeFile}
 * precedent: an orthogonal capability marker, deliberately outside the sealed hierarchy so a
 * variant opts in without the hierarchy being restructured. Consumers are the render-side form
 * resolution in {@code ObjectTypeGenerator}, the {@code additionalType} resolution in
 * {@code GraphitronSchemaBuilder}, and the scalar-demand sweep in {@code ConnectionPromoter},
 * which reaches every minted form through this accessor rather than restating the arm list.
 */
public interface CarriesObjectForm {

    /** The graphql-java object this classification's emission renders. */
    GraphQLObjectType schemaType();

    /**
     * The graphql-java form for {@code variant}: its own when it carries one, otherwise the
     * {@code assembled} schema's type of that name, or {@code null} when neither has one. The
     * single home of that resolution, so the emitters and the build-time guards that sweep the
     * emitted population read one rule.
     */
    static GraphQLNamedType formOf(GraphitronType variant, String name, GraphQLSchema assembled) {
        if (variant instanceof CarriesObjectForm carrier) {
            return carrier.schemaType();
        }
        return assembled.getType(name) instanceof GraphQLNamedType named ? named : null;
    }
}
