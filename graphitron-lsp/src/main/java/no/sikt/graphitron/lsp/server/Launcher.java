package no.sikt.graphitron.lsp.server;

import no.sikt.graphitron.lsp.state.Workspace;

import java.util.concurrent.Executors;

/**
 * stdio entry point for the LSP. Editors / Maven mojos spawn this main.
 * The lsp4j {@code Launcher} handles the JSON-RPC framing on stdin/stdout.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) throws Exception {
        // The drain gets its own daemon thread so didOpen never blocks the stdin reader. No
        // explicit shutdown: this process lives exactly as long as its one connection, and the
        // daemon flag keeps JVM exit correct when stdin closes.
        var drainExecutor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "graphitron-lsp-diagnostics-drain");
            thread.setDaemon(true);
            return thread;
        });
        var server = new GraphitronLanguageServer(new Workspace(), uri -> {}, drainExecutor);
        var launcher = LauncherFactory.forStreams(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}
