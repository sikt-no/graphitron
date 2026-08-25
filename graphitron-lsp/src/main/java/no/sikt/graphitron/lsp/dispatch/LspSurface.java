package no.sikt.graphitron.lsp.dispatch;

/**
 * The language server's answering surfaces: the six request capabilities registered in
 * {@code GraphitronLanguageServer.initialize} plus the pushed diagnostics channel.
 *
 * <p>This is the second axis of the dispatch matrix. It is deliberately the registered
 * capability rather than the class that implements it: several surfaces chain more than one
 * provider, and a provider list is an implementation detail that should be free to change
 * without the matrix moving.
 *
 * <p>Each constant states whether it is keyed on a cursor the editor hands it. That is a
 * property of the surface, so it is declared here rather than listed again by whoever needs
 * it: {@code TriggerDispatchMatrixTest} asserts that a whole-document sweep is declined by
 * every cursor-keyed surface, and a hand-kept list there would leave a new surface outside
 * that guard until somebody remembered to add it. Declaring it on the constant makes the
 * answer arrive with the surface instead.
 *
 * @see TriggerDispatch
 */
public enum LspSurface {

    /** {@code textDocument/completion}. */
    COMPLETION(Keying.CURSOR),

    /** {@code textDocument/hover}. */
    HOVER(Keying.CURSOR),

    /** {@code textDocument/definition}. */
    DEFINITION(Keying.CURSOR),

    /**
     * {@code textDocument/references}. Cursor-keyed like definition and the reverse of it in
     * what it returns: definition converges on the one thing a name denotes, this fans out
     * over every other site in the schema that uses it.
     */
    REFERENCES(Keying.CURSOR),

    /**
     * {@code textDocument/inlayHint}. Not cursor-keyed: it arrives per visible region and
     * annotates every site in it.
     */
    INLAY_HINT(Keying.REGION),

    /**
     * {@code textDocument/codeAction}. Not cursor-keyed: it arrives for a range, and the
     * findings it offers actions for come from a document sweep.
     */
    CODE_ACTION(Keying.REGION),

    /**
     * {@code textDocument/publishDiagnostics}. Pushed rather than requested, so it is the one
     * surface with no cursor: it enumerates the coordinates in a document instead of being
     * handed one.
     */
    DIAGNOSTIC(Keying.PUSHED);

    /** What the surface is handed when it is asked to answer. */
    public enum Keying {

        /** A position in a document: the editor is asking about the token under the caret. */
        CURSOR,

        /** A range or a whole document: the surface enumerates the sites inside it. */
        REGION,

        /** Nothing; the server pushes when it has something to say. */
        PUSHED
    }

    private final Keying keying;

    LspSurface(Keying keying) {
        this.keying = keying;
    }

    /** What this surface is handed when asked to answer. */
    public Keying keying() {
        return keying;
    }

    /** Whether an editor hands this surface a cursor position. */
    public boolean isCursorKeyed() {
        return keying == Keying.CURSOR;
    }
}
