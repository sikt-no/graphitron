package no.sikt.graphitron.lsp.server;

import org.eclipse.lsp4j.jsonrpc.JsonRpcException;
import org.eclipse.lsp4j.jsonrpc.Launcher.Builder;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Builds the lsp4j launcher every transport uses, so one connection policy is configured
 * once rather than restated per entry point. Both callers pass a server and a stream pair
 * and get back a launcher they only have to connect and start: this package's own
 * {@link Launcher} (the stdio {@code main}) and the {@code dev} goal's socket server. A
 * third transport gets the policy by construction.
 *
 * <p>The policy is one judgement: a write that fails because the peer is gone is not news.
 * Disconnecting an editor leaves several requests part-answered, and each answer is then
 * written onto a socket that teardown has already closed. lsp4j has a name for that
 * condition, {@link JsonRpcException#indicatesStreamClosed}, and consults it on the read
 * side and for a failed notification's log level, but not on the response path, where its
 * default handler logs the failure at {@code SEVERE} with a stack trace. Wrapping the
 * message consumers applies lsp4j's own verdict at the one seam every outbound message
 * passes through.
 *
 * <p>Note the asymmetry: the records being suppressed are lsp4j's, written through
 * {@code java.util.logging}, while the debug line that replaces them goes through slf4j
 * like the rest of this codebase.
 */
public final class LauncherFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(LauncherFactory.class);

    private LauncherFactory() {}

    /**
     * A launcher for one connection. The return type is lsp4j's {@code Launcher}, spelled
     * out because the simple name in this package is the stdio entry point.
     *
     * <p>{@code wrapMessages} is the seam rather than {@code setExceptionHandler} because an
     * exception handler is only consulted on the request/response path: a
     * {@code publishDiagnostics} push to a departed client is a notification, whose write
     * failure lsp4j catches and logs itself before any handler is reached.
     */
    public static org.eclipse.lsp4j.jsonrpc.Launcher<LanguageClient> forStreams(
        LanguageServer server, InputStream in, OutputStream out
    ) {
        return new Builder<LanguageClient>()
            .setLocalService(server)
            .setRemoteInterface(LanguageClient.class)
            .setInput(in)
            .setOutput(out)
            .wrapMessages(LauncherFactory::quietOnStreamClosed)
            .create();
    }

    /**
     * Wraps one message consumer so a write to a departed peer is dropped quietly and
     * everything else still fails loudly.
     *
     * <p>The boundary is exactly {@link JsonRpcException#indicatesStreamClosed}, which
     * already enumerates the conditions that mean the peer is gone and recurses through
     * {@code JsonRpcException} causes itself. Matching on exception messages here would be a
     * second, worse copy of a predicate that ships in the dependency. The rethrow branch is
     * what keeps this from widening into a general write-error swallow: a framing or
     * serialisation failure is a real defect and stays visible.
     *
     * <p>Package-private so the predicate boundary can be pinned without a socket.
     */
    static MessageConsumer quietOnStreamClosed(MessageConsumer delegate) {
        return message -> {
            try {
                delegate.consume(message);
            } catch (RuntimeException e) {
                if (!JsonRpcException.indicatesStreamClosed(e)) {
                    throw e;
                }
                LOGGER.debug("dropped a {} for a peer that is gone: {}",
                    message.getClass().getSimpleName(), e.getMessage());
            }
        };
    }
}
