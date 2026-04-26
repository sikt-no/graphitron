package no.sikt.graphitron.lsp.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Spike-scope: empty. Lives next to the text-document service for symmetry.
 */
public class GraphitronWorkspaceService implements WorkspaceService {

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {}

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}
}
