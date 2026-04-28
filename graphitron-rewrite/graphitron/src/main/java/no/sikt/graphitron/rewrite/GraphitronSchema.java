package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed representation of a GraphQL schema. Holds all classified types and fields.
 *
 * <p>Types are keyed by name. The {@link #fields} map is the authoritative flat index of all
 * classified fields, keyed by {@link FieldCoordinates}. Use {@link #field} for O(1) point
 * lookups and {@link #fieldsOf} for O(1) per-type lookups (pre-grouped at construction time).
 *
 * <p>{@link #warnings} carries non-fatal advisories the builder accumulated during
 * classification — shape-parallel to the errors {@code GraphitronSchemaValidator} produces, but
 * never fail the build. Surfaced by the plugin's mojos to the Maven log.
 */
public record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields,
    Map<String, List<GraphitronField>> fieldsByType,
    Map<String, EntityResolution> entitiesByType,
    List<BuildWarning> warnings
) {

    /**
     * Two-arg convenience constructor: groups fields by {@code parentTypeName} automatically,
     * preserving insertion order (declaration order when the fields map is a {@link LinkedHashMap}).
     * No entity resolutions, no warnings.
     */
    public GraphitronSchema(Map<String, GraphitronType> types, Map<FieldCoordinates, GraphitronField> fields) {
        this(types, fields, groupByType(fields), Map.of(), List.of());
    }

    /**
     * Three-arg convenience constructor used by {@link GraphitronSchemaBuilder} — same
     * field-grouping as the two-arg form but preserves the {@code warnings} list the builder
     * accumulated during classification.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings) {
        this(types, fields, groupByType(fields), Map.copyOf(entitiesByType), List.copyOf(warnings));
    }

    private static Map<String, List<GraphitronField>> groupByType(Map<FieldCoordinates, GraphitronField> fields) {
        var grouped = new LinkedHashMap<String, List<GraphitronField>>();
        for (var field : fields.values()) {
            grouped.computeIfAbsent(field.parentTypeName(), k -> new ArrayList<>()).add(field);
        }
        return Map.copyOf(grouped);
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

    /**
     * Returns all fields belonging to {@code typeName}, in declaration order, or an empty list
     * if the type has no fields recorded in this schema.
     */
    public List<GraphitronField> fieldsOf(String typeName) {
        return fieldsByType.getOrDefault(typeName, List.of());
    }

    /**
     * Returns the federation entity-resolution metadata for {@code typeName}, or {@code null}
     * if the type carries no {@code @key} (and is not a {@code @node}). The classifier records
     * one entry here per type whose SDL declaration carries at least one resolvable
     * {@code @key} alternative; the runtime entity dispatcher consumes these to wire the
     * {@code _entities} fetcher.
     */
    public EntityResolution entityResolution(String typeName) {
        return entitiesByType.get(typeName);
    }

}
