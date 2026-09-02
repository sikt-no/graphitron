package no.sikt.graphitron.rewrite;

import java.util.List;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * Build-pipeline validator output. Carries the full {@link ValidationError} and
 * {@link BuildWarning} lists produced by {@link GraphitronSchemaValidator#validate} plus
 * {@link GraphitronSchema#warnings}, and nothing derived from them: the diagnostics consumers read
 * the fact store's own {@code diagnostic} view, so a report is the two lists and the emptiness
 * question asked of them.
 */
public record ValidationReport(
    List<ValidationError> errors,
    List<BuildWarning> warnings
) {

    public ValidationReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public static ValidationReport empty() {
        return new ValidationReport(List.of(), List.of());
    }

    /** Factory: bundles validator errors and schema warnings as the pipeline produced them. */
    public static ValidationReport from(List<ValidationError> errors, List<BuildWarning> warnings) {
        return new ValidationReport(errors, warnings);
    }

    public boolean isEmpty() {
        return errors.isEmpty() && warnings.isEmpty();
    }
}
