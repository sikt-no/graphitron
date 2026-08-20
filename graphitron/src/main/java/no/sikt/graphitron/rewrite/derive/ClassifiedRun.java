package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.GraphitronSchema;

import java.util.Objects;

/**
 * Whether the run that is capturing produced a classified model, and what the classification
 * resolved if it did. The capture-and-detect pass's run-mode discriminator, stated as a value
 * rather than inferred from a null: a run whose document a stage refused has no classified model
 * at all, and the detections it would otherwise mint have no build to fail.
 *
 * <p>Sealed with two arms rather than a nullable component, because the two arms differ in what
 * they carry and not merely in whether a field is filled: {@link Absent} carries nothing on
 * purpose, and every member {@link Present} grows is a fact about a classification that happened.
 */
public sealed interface ClassifiedRun {

    /**
     * A run with no classified model: a stage refused the document, or the caller never asked for
     * classification. The detections do not run and the walk-side relations get no rows, which is
     * the same emptiness a run whose classification found nothing would leave.
     */
    record Absent() implements ClassifiedRun {}

    /**
     * A run whose classification completed, carrying what the walk resolved and the store
     * transcribes. One component today; the arm exists so a second is a component here rather
     * than another parameter on every capture entry point.
     */
    record Present(TypeBackingClasses backingClasses) implements ClassifiedRun {
        public Present {
            Objects.requireNonNull(backingClasses, "backingClasses");
        }
    }

    /** The no-classified-model arm. */
    static ClassifiedRun absent() {
        return new Absent();
    }

    /** The classified arm, projected from the walked model. */
    static ClassifiedRun of(GraphitronSchema schema) {
        return new Present(TypeBackingClasses.of(schema));
    }
}
