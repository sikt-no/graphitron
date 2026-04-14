package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.List;
import java.util.Map;

/**
 * The parsed representation of a GraphQL schema. Holds all classified types and fields.
 *
 * <p>Types are keyed by name. Output types ({@link GraphitronType.TableType},
 * {@link GraphitronType.NodeType}, and {@link GraphitronType.RootType}) carry a
 * {@code fieldCoordinates()} list recording the schema coordinates of their fields in declaration order.
 *
 * <p>The {@link #fields} map is the authoritative flat index of all classified fields, keyed by
 * {@link FieldCoordinates}. Use {@link #field} for O(1) point lookups and {@link #fieldsOf} to
 * retrieve all fields belonging to a given type.
 */
public record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields
) {

    /**
     * Returns the field at the given coordinates, or {@code null} if absent.
     */
    public GraphitronField field(String typeName, String fieldName) {
        return fields.get(FieldCoordinates.coordinates(typeName, fieldName));
    }

    /**
     * Returns the type with the given name, or {@code null} if absent.
     */
    public GraphitronType type(String typeName) {
        return types.get(typeName);
    }

    /**
     * Returns all fields belonging to {@code typeName}, in declaration order, or an empty list
     * if the type has no fields recorded in this schema.
     */
    public List<GraphitronField> fieldsOf(String typeName) {
        return fields.values().stream()
            .filter(f -> typeName.equals(f.parentTypeName()))
            .toList();
    }

}
