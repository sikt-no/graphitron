package no.sikt.graphitron.record;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.type.GraphitronType;

import java.util.Map;

/**
 * The parsed representation of a GraphQL schema. Holds all classified types and fields.
 *
 * <p>Types are keyed by name. Fields are keyed by {@link FieldCoordinates} — the GraphQL-spec
 * standardised {@code (typeName, fieldName)} pair provided by GraphQL Java, the same key used in
 * {@code GraphQLCodeRegistry}. The two maps are therefore parallel by construction.
 */
public record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields
) {
    /**
     * Returns the field at the given coordinates, or {@code null} if absent.
     */
    public GraphitronField get(String typeName, String fieldName) {
        return fields.get(FieldCoordinates.coordinates(typeName, fieldName));
    }

    /**
     * Returns the type with the given name, or {@code null} if absent.
     */
    public GraphitronType type(String typeName) {
        return types.get(typeName);
    }
}
