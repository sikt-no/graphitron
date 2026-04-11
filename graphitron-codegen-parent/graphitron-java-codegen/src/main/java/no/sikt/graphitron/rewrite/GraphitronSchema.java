package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parsed representation of a GraphQL schema. Holds all classified types and fields.
 *
 * <p>Types are keyed by name. Each {@link GraphitronType.OutputType} carries its own
 * {@link GraphitronField} list directly. The {@link #fields} map is a flat index derived from
 * those lists — it is keyed by {@link FieldCoordinates} (the GraphQL-spec standardised
 * {@code (typeName, fieldName)} pair provided by GraphQL Java) and exists solely to support
 * O(1) point lookups via {@link #field}.
 *
 * <p>Use the {@link #GraphitronSchema(Map)} single-argument constructor when building from a
 * fully-enriched types map; the flat {@link #fields} index is derived automatically. The
 * two-argument form is retained for tests that supply the two maps independently.
 */
public record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields
) {
    /**
     * Convenience constructor: builds the flat {@link #fields} index from the
     * {@link GraphitronType.OutputType#fields()} lists of all output types in {@code types}.
     */
    public GraphitronSchema(Map<String, GraphitronType> types) {
        this(types, buildFieldsIndex(types));
    }

    private static Map<FieldCoordinates, GraphitronField> buildFieldsIndex(Map<String, GraphitronType> types) {
        var result = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        types.values().forEach(t -> {
            if (t instanceof GraphitronType.OutputType ot) {
                ot.fields().forEach(f ->
                    result.put(FieldCoordinates.coordinates(f.parentTypeName(), f.name()), f));
            }
        });
        return Collections.unmodifiableMap(result);
    }

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
}
