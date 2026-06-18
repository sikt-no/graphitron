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
 * classification, shape-parallel to the errors {@code GraphitronSchemaValidator} produces but
 * never fail the build. Surfaced by the plugin's mojos to the Maven log.
 *
 * <p>{@link #contextArguments} is the cached output of {@link ContextArgumentClassifier}'s
 * cross-site type-agreement walk, computed once at construction time. Both downstream consumers
 * (the validator's {@code validateContextArgumentTypeAgreement} drain and
 * {@code GraphitronFacadeGenerator}'s factory parameter emission) read this field directly
 * rather than re-classifying, so a "single producer" guarantee holds across the two consumers.
 *
 * <p>{@link #diagnostics} (R317 slice 5, generalising the R204 / R279 slice 4
 * {@code domainReturnTypeConflicts} one-off) carries the build-time validation findings the
 * immutable validate phase accumulated instead of demoting a classified verdict to
 * {@code UnclassifiedType} / {@code UnclassifiedField}: the multi-producer {@code DomainReturnType}
 * disagreements, node-typeId collisions, case-fold collisions, the dangling-reference backstop, and
 * the federation {@code @key} checks. Each is a fully-formed {@link ValidationError} (coordinate,
 * typed {@link Rejection}, source location); the validator drains them into the same
 * {@link ValidationError} stream it emits today, so which schemas pass or fail is unchanged while a
 * verdict read after the walk equals the verdict classification produced. Empty for every
 * test-constructed schema and every error-free build.
 */
public record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields,
    Map<String, List<GraphitronField>> fieldsByType,
    Map<String, EntityResolution> entitiesByType,
    List<BuildWarning> warnings,
    ContextArgumentClassifier.Classification contextArguments,
    List<ValidationError> diagnostics
) {

    /**
     * Two-arg convenience constructor: groups fields by {@code parentTypeName} automatically,
     * preserving insertion order (declaration order when the fields map is a {@link LinkedHashMap}).
     * No entity resolutions, no warnings.
     */
    public GraphitronSchema(Map<String, GraphitronType> types, Map<FieldCoordinates, GraphitronField> fields) {
        this(types, fields, groupByType(fields), Map.of(), List.of(),
            ContextArgumentClassifier.classify(fields.values()), List.of());
    }

    /**
     * Convenience constructor used by {@link GraphitronSchemaBuilder}: same field-grouping as the
     * two-arg form but preserves the {@code warnings} list and the build-time {@code diagnostics}
     * the immutable validate phase accumulated.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics) {
        this(types, fields, groupByType(fields), Map.copyOf(entitiesByType), List.copyOf(warnings),
            ContextArgumentClassifier.classify(fields.values()), List.copyOf(diagnostics));
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
