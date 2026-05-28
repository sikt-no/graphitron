package no.sikt.graphitron.rewrite.model;

/**
 * Graphitron-side severity for {@link Diagnostic}, paralleling LSP {@code DiagnosticSeverity}
 * without leaking the lsp4j type below the LSP module boundary.
 */
public enum Severity {
    Error, Warning, Information, Hint
}
