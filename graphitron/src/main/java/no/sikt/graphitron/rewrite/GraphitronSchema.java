package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.Arrival;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Source;

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
 * <p>{@link #diagnostics} (generalising the {@code domainReturnTypeConflicts} one-off)
 * carries the build-time validation findings the
 * immutable validate phase accumulated instead of demoting a classified verdict to
 * {@code UnclassifiedType} / {@code UnclassifiedField}: the multi-producer {@code DomainReturnType}
 * disagreements, node-typeId collisions, case-fold collisions, the dangling-reference backstop, and
 * the federation {@code @key} checks. Each is a fully-formed {@link ValidationError} (coordinate,
 * typed {@link Rejection}, source location); the validator drains them into the same
 * {@link ValidationError} stream it emits today, so which schemas pass or fail is unchanged while a
 * verdict read after the walk equals the verdict classification produced. Empty for every
 * test-constructed schema and every error-free build.
 *
 * <p>{@link #arrivals} is the ancestor-product arrival fold, a typename-keyed index computed
 * once over the assembled SDL ({@code ArrivalIndex}). It is the ancestor fact {@link #sourceOf} threads
 * into {@link OutputField#source(Arrival)} to pick the {@code OnlyChild} / {@code Child} arm; arrival is
 * a parent-typename-grain fact, so it lives here rather than as a per-leaf component. Empty for
 * test-constructed schemas, which then fold every nested field to the conservative absorbing
 * {@link Arrival#MANY} ({@code Child}).
 */
public record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields,
    Map<String, List<GraphitronField>> fieldsByType,
    Map<String, EntityResolution> entitiesByType,
    List<BuildWarning> warnings,
    ContextArgumentClassifier.Classification contextArguments,
    List<ValidationError> diagnostics,
    Map<String, Arrival> arrivals
) {

    /**
     * Two-arg convenience constructor: groups fields by {@code parentTypeName} automatically,
     * preserving insertion order (declaration order when the fields map is a {@link LinkedHashMap}).
     * No entity resolutions, no warnings, empty arrival index (every nested field folds to
     * {@link Arrival#MANY}).
     */
    public GraphitronSchema(Map<String, GraphitronType> types, Map<FieldCoordinates, GraphitronField> fields) {
        this(types, fields, groupByType(fields), Map.of(), List.of(),
            ContextArgumentClassifier.classify(fields.values()), List.of(), Map.of());
    }

    /**
     * Convenience constructor used by {@link GraphitronSchemaBuilder}: same field-grouping as the
     * two-arg form but preserves the {@code warnings} list, the build-time {@code diagnostics} the
 * immutable validate phase accumulated, and the {@code arrivals} arrival index.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics,
                            Map<String, Arrival> arrivals) {
        this(types, fields, groupByType(fields), Map.copyOf(entitiesByType), List.copyOf(warnings),
            ContextArgumentClassifier.classify(fields.values()), List.copyOf(diagnostics), Map.copyOf(arrivals));
    }

    /**
     * Five-arg convenience constructor (the pre-arrival-index shape, retained for tests): no arrival index, so
     * every nested field folds to the conservative {@link Arrival#MANY} ({@code Child}).
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics) {
        this(types, fields, entitiesByType, warnings, diagnostics, Map.of());
    }

    /**
     * Seven-arg convenience constructor (the pre-arrival-index canonical shape, retained for tests that supply
     * a pre-grouped {@code fieldsByType} and an explicit {@link ContextArgumentClassifier.Classification}):
     * no arrival index.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, List<GraphitronField>> fieldsByType,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            ContextArgumentClassifier.Classification contextArguments,
                            List<ValidationError> diagnostics) {
        this(types, fields, fieldsByType, entitiesByType, warnings, contextArguments, diagnostics, Map.of());
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
 * The field's {@code source} arrival endpoint, folding the parent type's ancestor-product
     * {@link Arrival} into {@link OutputField#source(Arrival)}. The single seam consumers read the
     * arrival arm through: a nested field on a {@link Arrival#ONE} parent yields {@link Source.OnlyChild},
     * else {@link Source.Child}; a root field yields {@link Source.Root} (the empty product ignores the
     * arrival). A missing arrival entry folds to the absorbing {@link Arrival#MANY}, so an incompletely
     * indexed schema can never mint a spurious {@code OnlyChild}. Returns {@code null} when the coordinate
     * is absent or does not classify to an {@link OutputField}.
     */
    public Source sourceOf(FieldCoordinates coord) {
        if (!(fields.get(coord) instanceof OutputField out)) {
            return null;
        }
        return out.source(arrivals.getOrDefault(coord.getTypeName(), Arrival.MANY));
    }

    /** {@link #sourceOf(FieldCoordinates)} keyed by type/field name. */
    public Source sourceOf(String typeName, String fieldName) {
        return sourceOf(FieldCoordinates.coordinates(typeName, fieldName));
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
