package no.sikt.graphitron.rewrite.derive;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;

import java.util.Set;

/**
 * The coordinates the classification walk visited: the walked model's type and field registries
 * as membership sets. The conflict detection mints only inside this domain, a membership test
 * and nothing more, because the legacy detector sites lived on the walk and the walk's reach is
 * narrower than capture's. The gate's full rationale and removal criterion live on the reified
 * form's relation comments ({@code walk_claim_domain_type} / {@code walk_claim_domain_field},
 * written from this value by {@link ClaimDomainRows} at capture cadence), which the
 * {@code intent_authored_claim_conflict} view joins so the gate is a join on the store side.
 *
 * <p>This value is the unreified demand relation. The demand and exemption derivations now
 * exist in the store ({@code intent_type_domain}, the rule views and the resolved reductions
 * over them) and are diffed against exactly this value by their shadow agreement (see
 * {@code no.sikt.graphitron.rewrite.derive.DemandShadowTest}), with the populations the store
 * cannot yet express named by {@link DemandResidue}. The gate dissolves when the detection
 * reads the demand relation instead of the walked model, which is the gate-flip follow-up's
 * work, not the shadow's.
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
