package no.sikt.graphitron.rewrite.derive;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;

import java.util.Set;

/**
 * The coordinates the classification walk visited: the walked model's type and field registries
 * as membership sets. The conflict detection mints only inside this domain, a membership test
 * and nothing more, because the legacy detector sites lived on the walk and the walk's reach is
 * narrower than capture's (capture is total, with no reachability pruning). Interface fields,
 * directiveless nesting targets, connection machinery and the other exemption populations the
 * demand exemption census recorded (2026-08-06, in the roadmap's audit records) never reached a
 * detector, and an ungated detection would move the accept line exactly there.
 *
 * <p>A scaffold with a stated removal criterion: this value is the unreified demand relation.
 * The demand and exemption derivations now exist in the store ({@code intent_type_domain}, the
 * rule views and the resolved reductions over them) and are diffed against exactly this value
 * by their shadow agreement (see {@code no.sikt.graphitron.rewrite.derive.DemandShadowTest}),
 * with the populations the store cannot yet express named by {@link DemandResidue}. The gate
 * dissolves when the detection reads the demand relation instead of the walked model, which is
 * the gate-flip follow-up's work, not the shadow's.
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
