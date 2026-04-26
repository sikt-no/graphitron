package no.sikt.graphitron.lsp.server;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.services.TextDocumentService;

/**
 * Spike-scope: empty. Notification stubs satisfy the lsp4j contract; real
 * handlers are wired as the port progresses (workspace, file lifecycle,
 * directive dispatch). Until then the launcher exists to validate the
 * end-to-end Maven invocation only.
 */
public class GraphitronTextDocumentService implements TextDocumentService {

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {}

    @Override
    public void didChange(DidChangeTextDocumentParams params) {}

    @Override
    public void didClose(DidCloseTextDocumentParams params) {}

    @Override
    public void didSave(DidSaveTextDocumentParams params) {}
}
