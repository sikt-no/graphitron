package no.sikt.graphitron.rewrite.derive;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;

import java.util.Set;

/**
 * The coordinates the classification walk visited: the walked model's type and field registries
 * as membership sets.
 *
 * <p>This value is the unreified demand relation, and diffing against it is the only thing left
 * that reads it. The demand and exemption derivations exist in the store (the rule views and the
 * resolved reductions over them) and are diffed against exactly this value by their shadow
 * agreement (see {@code no.sikt.graphitron.rewrite.derive.DemandShadowTest}), with the populations
 * the store cannot yet express named by {@link DemandResidue}. Nothing reifies it: the
 * authored-claim conflict detection used to gate on a membership set written from here, and now
 * reads a relation total over the authored claims with each consumer applying its own population,
 * so the two membership relations this value was transcribed into were deleted rather than
 * re-pointed. The value itself retires with the shadow that reads it.
 */
public record ClaimDomain(Set<String> typeNames, Set<FieldCoordinates> fieldCoordinates) {

    public ClaimDomain {
        typeNames = Set.copyOf(typeNames);
        fieldCoordinates = Set.copyOf(fieldCoordinates);
    }

    /** The walked model's registries, projected to the membership sets the detection gates on. */
    public static ClaimDomain of(GraphitronSchema schema) {
        return new ClaimDomain(schema.types().keySet(), schema.fields().keySet());
    }

    /** Whether the walk registered the type, tombstones included. */
    public boolean containsType(String typeName) {
        return typeNames.contains(typeName);
    }

    /** Whether the walk registered the field coordinate, tombstones included. */
    public boolean containsField(String typeName, String fieldName) {
        return fieldCoordinates.contains(FieldCoordinates.coordinates(typeName, fieldName));
    }
}
