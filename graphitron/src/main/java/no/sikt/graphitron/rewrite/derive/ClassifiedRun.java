package no.sikt.graphitron.rewrite.derive;

/**
 * Whether the run that is capturing produced a classified model. The capture-and-detect pass's
 * run-mode discriminator, stated as a value rather than inferred from a null: a run whose document
 * a stage refused has no classified model at all, and the detections it would otherwise mint have
 * no build to fail.
 *
 * <p>Sealed with two arms rather than a boolean, because an arm may grow a component: {@link
 * Absent} carries nothing on purpose, and anything {@link Present} comes to carry is a fact about a
 * classification that happened.
 */
public sealed interface ClassifiedRun {

    /**
     * A run with no classified model: a stage refused the document, or the caller never asked for
     * classification. The detections do not run, which is the same emptiness a run whose
     * classification found nothing would leave.
     */
    record Absent() implements ClassifiedRun {}

    /** A run whose classification completed. */
    record Present() implements ClassifiedRun {}

    /** The no-classified-model arm. */
    static ClassifiedRun absent() {
        return new Absent();
    }

    /** The classified arm. */
    static ClassifiedRun present() {
        return new Present();
    }
}
