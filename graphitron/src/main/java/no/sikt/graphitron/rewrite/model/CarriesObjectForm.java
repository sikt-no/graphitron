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
     * The graphql-java form for {@code variant}: the {@code assembled} schema's type of that name,
     * otherwise its own when it carries one, or {@code null} when neither has one. The single home
     * of that resolution, so the emitters and the build-time guards that sweep the emitted
     * population read one rule.
     *
     * <p>The schema first: handed the post-synthesis schema, a minted type renders as the store
     * minted it, with what it inherits from the configuration, rather than as the walk synthesised
     * it from the schema as written. The carried form answers only where the schema has no such
     * type, which is the walk's own schema before its synthesis is added.
     */
    static GraphQLNamedType formOf(GraphitronType variant, String name, GraphQLSchema assembled) {
        if (assembled.getType(name) instanceof GraphQLNamedType named) {
            return named;
        }
        return variant instanceof CarriesObjectForm carrier ? carrier.schemaType() : null;
    }
}
