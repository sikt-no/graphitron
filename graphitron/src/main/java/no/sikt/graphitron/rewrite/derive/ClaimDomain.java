package no.sikt.graphitron.rewrite.derive;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;

import java.util.Set;

/**
 * The coordinates the classification walk visited: the walked model's type and field registries
 * as membership sets.
 *
 * <p>Read only by a test comparing the store's type-backing answer with the walk's. The store
 * relations this value was once diffed against, which coordinates the generator owes a verdict,
 * were dissolved: the generator never read them, and the one surface that did restated directives
 * it already showed. The value retires with its last reader.
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
