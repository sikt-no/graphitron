package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.PivotSpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The nesting/pivot reach fold: every type reached as a nesting projection (or a pivot
 * projection, riding the same seams through a synthetic wiring) from a table-backed root,
 * keyed to its one representative wiring. One walk with one first-occurrence order (roots
 * sorted by name, fields in declaration order, depth-first), because the representative decides
 * emitted content (the nested {@code <Type>Fetchers} class's table and field set) and two walks
 * picking different representatives for a shared nested type would silently change output; the
 * reach's three consumers (the type-unit producer's fetchers membership, the fetcher
 * generator's per-row nested build, the registrations emitter's nested bodies) all read this
 * fold.
 *
 * <p>Deliberately distinct from the fetcher generator's dual-shape pairing index, which walks
 * global field order: that index answers "which nesting arm pairs a mixed-source coordinate",
 * a per-coordinate read question, where this fold answers "which types own a nested class and
 * under which wiring". The two orders can pick different representatives for one type; that
 * divergence is a recorded fact of the retired two-pass emitter, named here rather than
 * silently fused (its repair is a key widening on the type-unit relation, tracked on the
 * roadmap).
 */
public record NestingReach(Map<String, ChildField.NestingField> representatives) {

    public NestingReach {
        representatives = Map.copyOf(representatives);
    }

    /** The reached type names in walk order (first-occurrence). */
    public List<String> reachedTypeNames() {
        return List.copyOf(representatives.keySet());
    }

    /** The representative wiring for a reached type, or {@code null} when not reached. */
    public ChildField.NestingField wiringFor(String typeName) {
        return representatives.get(typeName);
    }

    /**
     * Whether a nested type owns any fetcher: at least one of its fields classified. The one
     * gate shared by the emitted {@code <Type>Fetchers} class's membership row and the
     * registrations body, so the reference site and the emit site cannot drift.
     */
    public static boolean ownsFetchers(List<? extends GraphitronField> nestedFields) {
        return nestedFields.stream().anyMatch(f -> !(f instanceof GraphitronField.UnclassifiedField));
    }

    static NestingReach compute(GraphitronSchema schema) {
        var representatives = new LinkedHashMap<String, ChildField.NestingField>();
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableBackedType)
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> schema.fieldsOf(e.getKey()).forEach(f -> {
                // Exactly the retired pass's two layers: a root-level nesting edge starts the
                // NestingField-only descent, and a root-level pivot leaf joins through its
                // synthetic wiring (whose slots' nesting edges then descend the same way). A
                // pivot leaf nested deeper is deliberately NOT reached, mirroring the retired
                // recursion (which descended NestingFields alone); widening that reach is an
                // emission change, not a relocation.
                if (f instanceof ChildField.NestingField nf) {
                    collect(nf, representatives);
                }
                var spec = pivotSpecOf(f);
                if (spec != null) {
                    collect(pivotWiring(spec), representatives);
                }
            }));
        return new NestingReach(representatives);
    }

    private static void collect(ChildField.NestingField nf, Map<String, ChildField.NestingField> out) {
        out.putIfAbsent(nf.returnType().returnTypeName(), nf);
        for (var nested : nf.nestedFields()) {
            if (nested instanceof ChildField.NestingField inner) {
                collect(inner, out);
            }
        }
    }

    /** The {@link PivotSpec} of a pivot leaf, else {@code null}. */
    public static PivotSpec pivotSpecOf(GraphitronField field) {
        return switch (field) {
            case ChildField.PivotField pf -> pf.spec();
            case ChildField.BatchedPivotField bpf -> bpf.spec();
            default -> null;
        };
    }

    /**
     * An emit-time wiring carrier for a pivot edge, in the shape the nested-type seams already
     * consume ({@code returnTypeName} / {@code table} / {@code nestedFields}). Never registered
     * in the model: it exists only so the pivot edge rides the reach walk and the nested emit
     * seams without forking them on a second wiring type.
     */
    public static ChildField.NestingField pivotWiring(PivotSpec spec) {
        return new ChildField.NestingField(spec.projectionTypeName(), spec.projectionTypeName(), null,
            new ReturnTypeRef.TableBoundReturnType(spec.projectionTypeName(), spec.pivotTable(),
                new FieldWrapper.Single(true)),
            List.copyOf(spec.slots()));
    }
}
