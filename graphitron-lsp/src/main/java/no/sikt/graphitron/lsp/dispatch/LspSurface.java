package no.sikt.graphitron.lsp.dispatch;

/**
 * The language server's answering surfaces: the five request capabilities registered in
 * {@code GraphitronLanguageServer.initialize} plus the pushed diagnostics channel.
 *
 * <p>This is the second axis of the dispatch matrix. It is deliberately the registered
 * capability rather than the class that implements it: several surfaces chain more than one
 * provider, and a provider list is an implementation detail that should be free to change
 * without the matrix moving.
 *
 * @see TriggerDispatch
 */
public enum LspSurface {

    /** {@code textDocument/completion}. */
    COMPLETION,

    /** {@code textDocument/hover}. */
    HOVER,

    /** {@code textDocument/definition}. */
    DEFINITION,

    /** {@code textDocument/inlayHint}. */
    INLAY_HINT,

    /** {@code textDocument/codeAction}. */
    CODE_ACTION,

    /**
     * {@code textDocument/publishDiagnostics}. Pushed rather than requested, so it is the one
     * surface with no cursor: it enumerates the coordinates in a document instead of being
     * handed one.
     */
    DIAGNOSTIC
}
