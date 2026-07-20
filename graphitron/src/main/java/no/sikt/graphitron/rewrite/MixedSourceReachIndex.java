package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ReachableSourceShape;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Post-walk fold computing the reified reachable-source-shape fact ({@link GraphitronSchema#reachableSourceShapes})
 * for every coordinate reached through more than one source shape. The union "which source shapes reach
 * this coordinate" is a genuine cross-edge model fact; computing it once here (rather than letting the
 * dispatch emitter and the validator each reconstruct it from {@code NestingField} edges plus the type
 * registry) keeps the two consumers reading one fact.
 *
 * <p>A directiveless output type is reached as a nesting projection when a {@code ChildField.NestingField}
 * embeds it (the generic-{@link org.jooq.Record} shape) and, when a producer also binds it on the result
 * axis, as a class-backed accessor or a jOOQ-record carrier (the type's own {@code ResultType}
 * classification). A type reached both ways is "mixed-source": every field coordinate of it carries the
 * two-shape union. Single-reach coordinates are left absent; {@link GraphitronSchema#reachableSourceShapes}
 * derives their singleton on read.
 */
final class MixedSourceReachIndex {

    private MixedSourceReachIndex() {}

    static Map<FieldCoordinates, Set<ReachableSourceShape>> compute(
            Map<String, GraphitronType> types,
            Map<FieldCoordinates, GraphitronField> fields) {

        // Type names embedded by some NestingField edge (recursively), i.e. reached as a nesting
        // projection of a table-backed parent.
        var nestingReached = new LinkedHashSet<String>();
        fields.values().forEach(f -> collectNestingReached(f, nestingReached));

        var out = new LinkedHashMap<FieldCoordinates, Set<ReachableSourceShape>>();
        for (var entry : types.entrySet()) {
            if (!nestingReached.contains(entry.getKey())) {
                continue;
            }
            var resultShape = resultAxisShape(entry.getValue());
            if (resultShape == null) {
                // Pure nesting target (registered NestingType) or a non-result classification: single reach.
                continue;
            }
            var union = Set.of(ReachableSourceShape.NESTING_RECORD, resultShape);
            for (var field : fields.values()) {
                if (field.parentTypeName().equals(entry.getKey())) {
                    out.put(FieldCoordinates.coordinates(entry.getKey(), field.name()), union);
                }
            }
        }
        return out;
    }

    /**
     * The result-axis shape a type contributes when it is also nesting-reached, or {@code null} when the
     * type carries no producer-backed result classification (a pure {@code NestingType} or any other kind),
     * in which case a nesting-reached coordinate is single-reach.
     */
    private static ReachableSourceShape resultAxisShape(GraphitronType type) {
        return switch (type) {
            case GraphitronType.JooqRecordCarrier ignored -> ReachableSourceShape.JOOQ_RECORD_CARRIER;
            case GraphitronType.JavaRecordType ignored -> ReachableSourceShape.CLASS_BACKED_ACCESSOR;
            case GraphitronType.PojoResultType ignored -> ReachableSourceShape.CLASS_BACKED_ACCESSOR;
            case null, default -> null;
        };
    }

    private static void collectNestingReached(GraphitronField field, Set<String> out) {
        if (!(field instanceof ChildField.NestingField nf)) {
            return;
        }
        out.add(nf.returnType().returnTypeName());
        nf.nestedFields().forEach(child -> collectNestingReached(child, out));
    }
}
