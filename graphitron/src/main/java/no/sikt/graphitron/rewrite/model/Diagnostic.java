package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * A walker-produced diagnostic in the LSP-shape wire conventions, before projection to the
 * lsp4j {@code Diagnostic} at the LSP module boundary.
 *
 * <p>{@code severity} carries the graphitron-side {@link Severity}; the LSP projector maps
 * arms to {@code DiagnosticSeverity}.
 *
 * <p>{@code code} is a stable wire identifier like {@code graphitron.service-method-call.ambiguous-method}.
 * Codes are stable strings written next to each producer arm, not derived from Java identifiers,
 * so a wire contract can survive Java rename refactors.
 *
 * <p>{@code source} is always {@code "graphitron"}.
 *
 * <p>{@code primaryLocation} is the field's own SDL location; nested-path errors put the
 * offending segment in {@code relatedInformation} rather than retargeting.
 */
public record Diagnostic(
    Severity severity,
    String code,
    String source,
    String message,
    SourceLocation primaryLocation,
    List<RelatedInformation> relatedInformation
) {
    public Diagnostic {
        relatedInformation = List.copyOf(relatedInformation);
    }

    /**
     * Auxiliary location entry attached to a primary diagnostic. Mirrors LSP
     * {@code DiagnosticRelatedInformation}; used by the LSP projector to materialise
     * lsp4j wire objects.
     */
    public record RelatedInformation(SourceLocation location, String message) {}
}
