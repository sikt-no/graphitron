package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Field-axis classification registry. Two named operations:
 *
 * <ul>
 *   <li>{@link #classify(FieldCoordinates, GraphitronField)} — output-field path. Writes
 *       to a private {@code Map<FieldCoordinates, GraphitronField>} (the previously bare
 *       {@code fields} map in {@code GraphitronSchemaBuilder.buildSchema}, lifted into the
 *       registry) and emits a trace record. Asserts no prior entry.
 *   <li>{@link #classifyInput(String, String, SourceLocation, InputFieldResolution)} —
 *       input-field path. Trace-only: input fields are embedded in the parent
 *       {@code InputType} / {@code TableInputType} or {@code ArgumentRef.PlainInputArg.fields()}
 *       by their owner, not stored in a central map, so the registry doesn't own their
 *       persistence. Both {@link InputFieldResolution.Resolved} and
 *       {@link InputFieldResolution.Unresolved} arms emit one record per call.
 * </ul>
 *
 * <p>Architectural enforcement is honestly asymmetric. For output fields, the private map
 * prevents a bypass without adding a public method on the registry. For input fields, the
 * registry is the canonical trace point but {@code BuildContext.classifyInputField} remains
 * the sole producer by convention; a future bypass would have to introduce a parallel
 * input-classifier path that doesn't route through the registry.
 */
public final class FieldRegistry {

    private final Map<FieldCoordinates, GraphitronField> fields = new LinkedHashMap<>();

    /** Register an output field. Throws if {@code coords} are already registered. */
    public void classify(FieldCoordinates coords, GraphitronField field) {
        Objects.requireNonNull(coords, "coords");
        Objects.requireNonNull(field, "field");
        if (fields.containsKey(coords)) {
            throw new IllegalStateException("classify(" + coords + "): already classified as "
                + fields.get(coords).getClass().getSimpleName());
        }
        fields.put(coords, field);
        traceOutput(field);
    }

    /**
     * Replace an existing classification for {@code coords}. Used only by R156's DELETE
     * carrier-walk path, where the verbless walk's {@code GraphitronSchemaBuilder.registerCarrierDataField}
     * registers a {@link no.sikt.graphitron.rewrite.model.ChildField.SingleRecordTableField} on
     * the carrier's data field before {@link no.sikt.graphitron.rewrite.FieldBuilder} knows the
     * owning mutation's {@link no.sikt.graphitron.rewrite.model.DmlKind}; FieldBuilder reclassifies
     * to {@link no.sikt.graphitron.rewrite.model.ChildField.SingleRecordTableFieldFromReturning}
     * (the DELETE-specific sibling with the {@link no.sikt.graphitron.rewrite.model.PkResolution}
     * projection) once the verb is in scope. The {@code expectedExistingClass} parameter is the
     * structural guard: if the verbless walk wrote something other than the expected sibling,
     * the reclassification fails fast rather than silently overwriting a divergent shape.
     *
     * <p>{@link #classify} stays the duplicate-rejecting entry point for every other call site;
     * this method is the explicit, named exception that the carrier-walk path is allowed to
     * take and no other path should reach for.
     */
    public void reclassify(FieldCoordinates coords, GraphitronField field,
                           Class<? extends GraphitronField> expectedExistingClass) {
        Objects.requireNonNull(coords, "coords");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(expectedExistingClass, "expectedExistingClass");
        var existing = fields.get(coords);
        if (existing == null) {
            // No pre-existing classification — admit; behave like classify(). This happens for
            // DataElement.Id carriers where the verbless walk leaves the data field
            // unregistered (encoder lookup needs the owning mutation's input @table).
            fields.put(coords, field);
            traceOutput(field);
            return;
        }
        if (!expectedExistingClass.isInstance(existing)) {
            throw new IllegalStateException("reclassify(" + coords + "): expected existing "
                + expectedExistingClass.getSimpleName() + " but got " + existing.getClass().getSimpleName());
        }
        fields.put(coords, field);
        traceOutput(field);
    }

    /**
     * Trace-only emission for an input-field classification. Both {@code Resolved} and
     * {@code Unresolved} resolutions emit one record per call; the leaf class is the
     * contained {@link InputField} record class on success, or
     * {@code GraphitronField.UnclassifiedField} on failure.
     */
    public void classifyInput(String parentTypeName, String fieldName, SourceLocation location,
            InputFieldResolution resolution) {
        Objects.requireNonNull(parentTypeName, "parentTypeName");
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(resolution, "resolution");
        if (!ClassificationTrace.isEnabled()) return;
        String source = location == null ? null : location.getSourceName();
        if (resolution instanceof InputFieldResolution.Resolved r) {
            ClassificationTrace.emit(ClassificationTrace.Op.classify, parentTypeName, fieldName,
                leafName(r.field().getClass()), source, null, null);
        } else if (resolution instanceof InputFieldResolution.Unresolved u) {
            // Unresolved carries no Rejection variant (the failure path doesn't produce an
            // UnclassifiedField; it's a transient resolution outcome consumed by the caller).
            // Default to AUTHOR_ERROR per the kind-of-thumb rule: typo-style column-miss and
            // path-resolution failures are the bulk of this arm.
            ClassificationTrace.emit(ClassificationTrace.Op.classify, parentTypeName, fieldName,
                "", source, RejectionKind.AUTHOR_ERROR, u.reason());
        }
    }

    /** Returns the current output-field classification for {@code coords}, or {@code null}. */
    public GraphitronField get(FieldCoordinates coords) {
        return fields.get(coords);
    }

    /** Read-only view of all output-field classifications, in insertion order. */
    public Map<FieldCoordinates, GraphitronField> entries() {
        return Collections.unmodifiableMap(fields);
    }

    private static void traceOutput(GraphitronField field) {
        if (!ClassificationTrace.isEnabled()) return;
        SourceLocation loc = field.location();
        String source = loc == null ? null : loc.getSourceName();
        if (field instanceof GraphitronField.UnclassifiedField u) {
            ClassificationTrace.emit(ClassificationTrace.Op.classify, field.parentTypeName(),
                field.name(), leafName(field.getClass()), source,
                RejectionKind.of(u.rejection()), u.rejection().message());
        } else {
            ClassificationTrace.emit(ClassificationTrace.Op.classify, field.parentTypeName(),
                field.name(), leafName(field.getClass()), source, null, null);
        }
    }

    private static String leafName(Class<?> c) {
        Class<?> enclosing = c.getEnclosingClass();
        if (enclosing != null) {
            return enclosing.getSimpleName() + "." + c.getSimpleName();
        }
        return c.getSimpleName();
    }
}
