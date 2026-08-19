package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.read.SourceUri;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Build-pipeline validator output. Carries the
 * full {@link ValidationError} and {@link BuildWarning} lists produced by
 * {@link GraphitronSchemaValidator#validate} plus
 * {@link GraphitronSchema#warnings}, alongside a precomputed canonical-URI set
 * for the short-circuit path in
 * {@code Diagnostics.compute}.
 *
 * <p>{@code sourceUris} is built once at construction by mapping every
 * {@link SourceLocation#getSourceName()} through {@link #canonicalUri(String)};
 * the LSP filter compares against the open file's URI with a single
 * {@code Set.contains}, so files with no validator output skip the per-error walk entirely.
 */
public record ValidationReport(
    List<ValidationError> errors,
    List<BuildWarning> warnings,
    Set<String> sourceUris
) {

    public ValidationReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
        sourceUris = Set.copyOf(sourceUris);
    }

    public static ValidationReport empty() {
        return new ValidationReport(List.of(), List.of(), Set.of());
    }

    /**
     * Factory: bundles validator errors and schema warnings, computing the canonical-URI set once
     * from every error/warning location. Skips locations with no usable {@code sourceName} or that
     * fail to parse as a path (defensive: production source names are file paths from
     * {@code trackData(true)} but unit-test fixtures may
     * carry placeholder strings).
     */
    public static ValidationReport from(List<ValidationError> errors, List<BuildWarning> warnings) {
        var uris = new LinkedHashSet<String>();
        for (var e : errors) {
            addCanonical(uris, e.location());
        }
        for (var w : warnings) {
            addCanonical(uris, w.location());
        }
        return new ValidationReport(errors, warnings, uris);
    }

    public boolean isEmpty() {
        return errors.isEmpty() && warnings.isEmpty();
    }

    /**
     * Canonical {@code file://} URI form of an SDL source path. Delegates to {@link SourceUri},
     * which owns both directions of the trip in the module that declares the columns they meet on,
     * so producer and consumer cannot drift on URL-encoding or path-form quirks.
     */
    public static String canonicalUri(String sourceName) {
        return SourceUri.of(sourceName);
    }

    private static void addCanonical(Set<String> uris, SourceLocation location) {
        if (location == null) return;
        String sourceName = location.getSourceName();
        if (sourceName == null || sourceName.isEmpty()) return;
        uris.add(canonicalUri(sourceName));
    }
}
