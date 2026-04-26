package no.sikt.graphitron.lsp.server;

import org.eclipse.lsp4j.jsonrpc.Launcher.Builder;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * stdio entry point for the LSP. Editors / Maven mojos spawn this main.
 * The lsp4j {@code Launcher} handles the JSON-RPC framing on stdin/stdout.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) throws Exception {
        var server = new GraphitronLanguageServer();
        var launcher = new Builder<LanguageClient>()
            .setLocalService(server)
            .setRemoteInterface(LanguageClient.class)
            .setInput(System.in)
            .setOutput(System.out)
            .create();
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}
