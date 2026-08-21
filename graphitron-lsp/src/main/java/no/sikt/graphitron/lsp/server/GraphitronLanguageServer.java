package no.sikt.graphitron.lsp.server;

import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.trace.LspTrace;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TraceValue;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * lsp4j entry point. Holds a single {@link Workspace} per server instance
 * (one server per editor connection); the workspace owns parsed files plus
 * the catalog. The {@code dev} Mojo constructs the catalog from
 * the rewrite generator and passes it in here.
 *
 * <p>The {@code onSchemaSaved} listener fires from {@code didSave}; the
 * dev Mojo wires it to the debounced regen trigger, so editor saves drive
 * regeneration directly rather than waiting for the filesystem watcher.
 * Headless LSP-only use sites (no dev Mojo) pass no listener and
 * {@code didSave} is a no-op.
 */
public class GraphitronLanguageServer implements LanguageServer, LanguageClientAware {

    private final Workspace workspace;
    private final GraphitronTextDocumentService textService;
    private final GraphitronWorkspaceService workspaceService;
    private LanguageClient client;

    public GraphitronLanguageServer() {
        this(new Workspace(), uri -> {});
    }

    public GraphitronLanguageServer(Workspace workspace) {
        this(workspace, uri -> {});
    }

    /**
     * Same-thread drain form: the diagnostics drain runs inline on whichever thread mutated the
     * workspace, so a publish is observable the moment the mutator returns. That happens-before is
     * what the synchronous test harnesses assert against; production connections use the executor
     * form below so the drain leaves the connection's message-reader thread.
     */
    public GraphitronLanguageServer(Workspace workspace, Consumer<String> onSchemaSaved) {
        this(workspace, onSchemaSaved, Runnable::run);
    }

    /**
     * @param drainExecutor where the diagnostics drain runs; see
     *     {@link GraphitronTextDocumentService#GraphitronTextDocumentService(Workspace, Consumer, Executor)}.
     *     The caller owns its lifetime: this server never shuts it down, so whoever minted it for a
     *     connection shuts it down when that connection ends.
     */
    public GraphitronLanguageServer(Workspace workspace, Consumer<String> onSchemaSaved, Executor drainExecutor) {
        this.workspace = workspace;
        this.textService = new GraphitronTextDocumentService(workspace, onSchemaSaved, drainExecutor);
        this.workspaceService = new GraphitronWorkspaceService(workspace);
    }

    public Workspace workspace() {
        return workspace;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        applyTraceValue(params.getTrace(), false);
        var capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        capabilities.setHoverProvider(true);
        capabilities.setCompletionProvider(new CompletionOptions(false, null));
        capabilities.setDefinitionProvider(true);
        capabilities.setCodeActionProvider(true);
        // Advertise the inlay-hint capability so editors that opt in via
        // graphitron.inlayHints.* config keys receive the inferred-directive and
        // classification hint surface. The handler is a no-op when all toggles default off.
        capabilities.setInlayHintProvider(true);
        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    /**
     * Pulls the three inlay-hint / hover toggles from the client immediately after
     * the initialize handshake completes. Mirrors the {@code workspace/didChangeConfiguration}
     * push path so editors that only push on user-initiated edits still see the right state
     * on first request. Clients that don't implement {@code workspace/configuration} return
     * a list of nulls (or fail the future); both fall through to the default-off behaviour
     * via {@link GraphitronWorkspaceService#applyPulledInlayHintConfig(java.util.List)}.
     */
    @Override
    public void initialized(InitializedParams params) {
        if (client == null) return;
        var configParams = new ConfigurationParams(List.of(
            sectionItem("graphitron")
        ));
        client.configuration(configParams).thenAccept(workspaceService::applyPulledInlayHintConfig);
    }

    private static ConfigurationItem sectionItem(String section) {
        var item = new ConfigurationItem();
        item.setSection(section);
        return item;
    }

    /**
     * Turns {@link LspTrace} on and off from the client, so a session that has started
     * misbehaving can be traced without relaunching the server with a system property.
     * Trace output still goes to the seam's own stream rather than back over the
     * connection; see {@link LspTrace} for why the protocol is the wrong carrier for a
     * diagnosis of the protocol.
     */
    @Override
    public void setTrace(SetTraceParams params) {
        applyTraceValue(params == null ? null : params.getValue(), true);
    }

    /**
     * Maps an LSP {@code TraceValue} onto the seam. {@code off} disables, {@code messages}
     * and {@code verbose} enable, and anything else (including absent) leaves the current
     * state alone.
     *
     * <p>{@code mayDisable} is what separates the two callers. A {@code $/setTrace}
     * notification is a deliberate mid-session act, so it is honoured in both directions.
     * The {@code trace} field on the initialize handshake is boilerplate most clients send
     * as {@code off} whether or not the user asked for anything, so honouring it there
     * would silence a deliberately-set {@code graphitron.lsp.trace} before the seam had
     * traced a single phase. At the handshake the value can therefore only turn tracing on.
     */
    private static void applyTraceValue(String value, boolean mayDisable) {
        if (value == null) {
            return;
        }
        switch (value) {
            case TraceValue.Messages, TraceValue.Verbose -> LspTrace.setEnabled(true);
            case TraceValue.Off -> {
                if (mayDisable) {
                    LspTrace.setEnabled(false);
                }
            }
            default -> { }
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // lsp4j drives process lifetime; nothing to clean up.
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        textService.setClient(client);
    }
}
