package no.sikt.graphitron.lsp.server;

import no.sikt.graphitron.lsp.code_action.CodeActions;
import no.sikt.graphitron.lsp.completions.Completions;
import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.definition.IntraSchemaDefinitions;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.state.StoreRead;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.trace.LspTrace;
import no.sikt.graphitron.model.boot.StoreAnswer;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Text-document handlers backed by a {@link Workspace}.
 *
 * <p>Lifecycle notifications populate / mutate the workspace and hand the
 * diagnostics drain for any files the workspace flagged for recalculation
 * to {@code drainExecutor}; the completion request resolves the directive
 * at the cursor and dispatches to the matching per-directive completion
 * provider.
 *
 * <p>The drain leaves the triggering thread because both threads that
 * trigger it are load-bearing: {@code didOpen} arrives on lsp4j's single
 * message-reader thread, so a drain there stops the server reading its own
 * input (every queued message waits, {@code $/cancelRequest} included),
 * and {@code markAllForRecalculation} arrives on the dev goal's watcher
 * thread, which the next build swap needs. The executor is single-threaded
 * by contract, not merely by frugality: two drains in flight would each
 * hold a read transaction on the one session-wide reader, so the second
 * serialises inside it anyway while holding a walk's worth of snapshots,
 * and their per-file publications could interleave so the client ends on
 * the older of two answers. One thread keeps the drain sequential, which
 * is what it was when it ran inline.
 */
public class GraphitronTextDocumentService implements TextDocumentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphitronTextDocumentService.class);

    private final Workspace workspace;
    private final Consumer<String> onSchemaSaved;
    private final Executor drainExecutor;
    // One pending drain, not a queue of them: every build calls markAllForRecalculation, so
    // submits arrive faster than drains complete under exactly the conditions that make a drain
    // slow. Set on submit, cleared when a drain starts, so N submits during one drain collapse
    // into one follow-up. Safe because the recalculation queue is the state: a drain takes
    // whatever drainRecalculate hands it, and a drain that finds nothing queued is a no-op.
    private final AtomicBoolean drainWanted = new AtomicBoolean();
    private LanguageClient client;

    public GraphitronTextDocumentService(Workspace workspace) {
        this(workspace, uri -> {});
    }

    public GraphitronTextDocumentService(Workspace workspace, Consumer<String> onSchemaSaved) {
        this(workspace, onSchemaSaved, Runnable::run);
    }

    /**
     * @param drainExecutor where the diagnostics drain runs. Production connections pass a
     *     single-thread daemon executor whose thread is named for the drain, so a stack dump of a
     *     stuck session says which thread it is on; the two-argument forms default to a same-thread
     *     executor, under which the drain completes before the triggering mutator returns, which is
     *     the happens-before the synchronous test harnesses assert against.
     */
    public GraphitronTextDocumentService(Workspace workspace, Consumer<String> onSchemaSaved, Executor drainExecutor) {
        this.workspace = workspace;
        this.onSchemaSaved = onSchemaSaved;
        this.drainExecutor = drainExecutor;
    }

    /**
     * Wired by {@link GraphitronLanguageServer#connect} once lsp4j has
     * exchanged capabilities and the client proxy exists. Until this
     * fires the service still works for tests that drive completions
     * without a paired client; diagnostic publishes are silently
     * skipped. Also registers the workspace's recalculate listener so
     * every queue-mutating workspace method (editor events plus the
     * build-trigger paths from {@code DevMojo}) drains and publishes
     * diagnostics through the same seam.
     */
    public void setClient(LanguageClient client) {
        this.client = client;
        workspace.setRecalculateListener(this::publishDiagnosticsForRecalculate);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var doc = params.getTextDocument();
        try (var span = LspTrace.span("didOpen")) {
            span.detail("uri", doc.getUri()).detail("chars", doc.getText().length());
            workspace.didOpen(doc.getUri(), doc.getVersion(), doc.getText());
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        var doc = params.getTextDocument();
        try (var span = LspTrace.span("didChange")) {
            span.detail("uri", doc.getUri())
                .detail("version", doc.getVersion())
                .detail("changes", params.getContentChanges().size());
            workspace.didChange(doc.getUri(), doc.getVersion(), params.getContentChanges());
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        try (var span = LspTrace.span("didClose")) {
            span.detail("uri", uri);
            // Clear any diagnostics the client may still be holding for the closed file. No other
            // file is affected: what each one shows is the graph's last capture judging it, which a
            // buffer closing does not change.
            if (client != null) {
                client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
            }
            workspace.didClose(uri);
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        try (var span = LspTrace.span("didSave")) {
            span.detail("uri", params.getTextDocument().getUri());
            onSchemaSaved.accept(params.getTextDocument().getUri());
        }
    }

    /**
     * Hands the drain of the workspace's recalculation queue to the drain
     * executor. No-op when the client has not connected yet (test
     * harnesses), and a submit that lands while a drain is pending or
     * running collapses into the one already wanted; the caller pays a
     * flag-and-submit, never the drain.
     *
     * <p>A rejected submit is absorbed here, never rethrown. The window is
     * real: the workspace outlives connections and its listener slot is not
     * cleared on teardown, so between an editor detaching (which shuts this
     * connection's executor down) and the next connection's {@link #setClient}
     * a build swap still reaches this service. Inline, that window was quiet,
     * lsp4j's {@code RemoteEndpoint.notify} catching its own write failure;
     * a mutator (the dev goal's watcher thread among them) must not gain a
     * throw the inline path never had. The flag is reset so it does not
     * record a drain that will never run, leaving every later mutation free
     * to submit again rather than collapsing into a phantom.
     */
    private void publishDiagnosticsForRecalculate() {
        if (client == null) return;
        if (drainWanted.compareAndSet(false, true)) {
            try {
                drainExecutor.execute(this::drainAndPublish);
            } catch (RejectedExecutionException e) {
                drainWanted.set(false);
                LOGGER.debug("diagnostics drain not scheduled, executor is shut down: {}", e.getMessage());
            }
        }
    }

    /**
     * The drain itself: walk every queued file, resolve the batch's
     * questions in one read transaction on the session-wide reader, publish
     * per file. Runs on the drain executor, so a drain bounded only by the
     * session read budget delays diagnostics rather than the connection's
     * message reader.
     */
    private void drainAndPublish() {
        // Cleared before the queue is read, so a mutation that lands after this point wants (and
        // gets) a fresh drain; one that landed before is already in the queue this drain takes.
        drainWanted.set(false);
        var queued = workspace.drainRecalculate();
        // Traced as one span around the whole drain: the outer duration is what the drain thread
        // spends per batch, no longer what a mutator pays. The breakdown inside it follows the
        // stages rather than the files, which is what separates "many files" from "one slow file"
        // now that the read is not per file: one walk span per file, all of them CPU over a tree, and
        // the store's single answer inside the one transaction below.
        try (var drainSpan = LspTrace.span("publishDiagnostics.drain")) {
            drainSpan.detail("files", queued.size());
            // Every queued file is walked first, which reads nothing, so the whole drain's questions
            // are known before any of them is resolved. A file with no open view is never added and
            // so is never published for, which is what the per-file null check used to say.
            var batch = new Diagnostics.Batch(workspace.vocabulary());
            var walked = new ArrayList<String>(queued.size());
            for (String uri : queued) {
                Boolean added = workspace.withView(uri, null, view -> {
                    batch.add(uri, view);
                    return Boolean.TRUE;
                });
                if (added != null) {
                    walked.add(uri);
                }
            }
            if (walked.isEmpty()) {
                return;
            }
            // One read transaction around the whole drain, so no two files are diagnosed from two
            // sides of a capture, and one statement per graph inside it rather than one per file.
            switch (workspace.answeringAll(StoreRead.DIAGNOSTICS, walked, batch::judgeAll)) {
                case StoreAnswer.Answered<Map<String, List<Diagnostic>>> answered ->
                    publish(walked, answered.value());
                // Nothing is published at all, which is not the same as publishing an empty list.
                // An empty list would erase the squiggles the last drain put on screen, so a read
                // that ran out of budget would clear the developer's warnings rather than leave
                // them standing; there is nothing here to replace them with, so nothing is sent.
                // The store boundary has already warned, naming the read.
                case StoreAnswer.OutOfBudget<Map<String, List<Diagnostic>>> ignored ->
                    drainSpan.detail("outOfBudget", true);
            }
        }
    }

    /**
     * Ships one diagnostic list per walked file, skipping any the drain had no answer for, and any
     * closed since the walk. The close check is the one hazard the executor introduces: inline, the
     * walk was proof the file was open when the publish went out; asynchronously, {@code didClose}
     * can land between them, and it publishes an empty list to clear the client's squiggles, so a
     * stale list sent after that clear would restore diagnostics for a buffer the developer closed.
     */
    private void publish(List<String> walked, Map<String, List<Diagnostic>> byUri) {
        for (String uri : walked) {
            var diagnostics = byUri.get(uri);
            if (diagnostics == null) continue;
            if (!workspace.holdsViewFor(uri)) continue;
            try (var fileSpan = LspTrace.span("publishDiagnostics.file")) {
                fileSpan.detail("uri", uri).detail("diagnostics", diagnostics.size());
                client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
            }
        }
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try (var span = LspTrace.span("codeAction")) {
                span.detail("uri", params.getTextDocument().getUri());
                var actions = CodeActions.compute(params, workspace);
                span.detail("actions", actions.size());
                return actions;
            }
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            try (var span = LspTrace.span("definition")) {
                span.detail("uri", uri);
                Either<List<? extends Location>, List<? extends LocationLink>> result =
                    workspace.withView(uri, null, file -> {
                        var pos = Positions.resolve(file.source(),
                            params.getPosition().getLine(),
                            params.getPosition().getCharacter()).tsPoint();
                        // IntraSchemaDefinitions takes its own withAllViews (the lock is
                        // released before this lambda runs, so that is not re-entrant); it
                        // returns a read-only Location, so a per-provider generation skew is
                        // harmless here in a way it would not be for a composed edit. All three
                        // providers share one read transaction for the same reason hover takes
                        // one: a chain that fell through to a second read could decline on a
                        // declaration the first read positioned.
                        StoreAnswer<Optional<Location>> found = workspace.answering(
                            StoreRead.DEFINITION, uri, store ->
                            Definitions.compute(workspace.vocabulary(), file, store, pos)
                                .or(() -> IntraSchemaDefinitions.compute(workspace, store, uri, pos))
                                .or(() -> DeclarationDefinitions.compute(file, store, pos)));
                        return switch (found) {
                            case StoreAnswer.Answered<Optional<Location>> answered -> answered.value()
                                .map(loc -> Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(loc)))
                                .orElseGet(() -> Either.forLeft(List.of()));
                            // No jump, which is what a cursor on nothing resolvable already gets.
                            // The developer keeps the buffer they are looking at; the store boundary
                            // has already warned, naming the read that overran.
                            case StoreAnswer.OutOfBudget<Optional<Location>> ignored ->
                                Either.forLeft(List.of());
                        };
                    });
                return result != null ? result : Either.forLeft(List.of());
            }
        });
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try (var span = LspTrace.span("inlayHint")) {
                span.detail("uri", params.getTextDocument().getUri());
                String uri = params.getTextDocument().getUri();
                // One read transaction around the whole region, as hover takes: two declarations
                // annotated from either side of a capture would disagree about the same schema.
                var hints = workspace.withView(uri, List.<InlayHint>of(), file ->
                    switch (workspace.answering(StoreRead.INLAY_HINTS, uri, store ->
                        InlayHints.compute(
                            workspace.inlayHintConfig(), file, store, params.getRange()))) {
                        case StoreAnswer.Answered<List<InlayHint>> answered -> answered.value();
                        // No hints for this region, which is what a region the store has nothing to
                        // annotate already gets. The next request over the same range is served
                        // normally; the store boundary has already warned, naming the read.
                        case StoreAnswer.OutOfBudget<List<InlayHint>> ignored -> List.<InlayHint>of();
                    });
                span.detail("hints", hints.size());
                return hints;
            }
        });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try (var span = LspTrace.span("hover")) {
                span.detail("uri", params.getTextDocument().getUri());
                String uri = params.getTextDocument().getUri();
                return workspace.withView(uri, null, file -> {
                    var pos = Positions.resolve(file.source(),
                        params.getPosition().getLine(),
                        params.getPosition().getCharacter()).tsPoint();
                    // One read transaction around the whole popup, as completion takes: a hover
                    // assembled from two snapshots could name a class from before a capture and
                    // describe it from after.
                    return switch (workspace.answering(StoreRead.HOVER, uri, store ->
                        Hovers.compute(workspace.vocabulary(), file, store, pos,
                            workspace.inlayHintConfig().hoverClassification()))) {
                        case StoreAnswer.Answered<Optional<Hover>> answered ->
                            answered.value().orElse(null);
                        // No popup, which is what a cursor on something the store has nothing to
                        // say about already gets. The store boundary has already warned.
                        case StoreAnswer.OutOfBudget<Optional<Hover>> ignored -> null;
                    };
                });
            }
        });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try (var span = LspTrace.span("completion")) {
                span.detail("uri", params.getTextDocument().getUri());
                return workspace.withView(params.getTextDocument().getUri(),
                    Either.<List<CompletionItem>, CompletionList>forLeft(List.of()), file -> {
                        // One snapshot feeds the position resolve, the directive scan, and
                        // Completions.at, so completion can no longer tear against an edit
                        // that lands between its own source and tree reads.
                        var pos = Positions.resolve(file.source(),
                            params.getPosition().getLine(),
                            params.getPosition().getCharacter()).tsPoint();
                        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
                        if (directiveOpt.isEmpty()) {
                            span.detail("directive", "none");
                            return Either.forLeft(List.of());
                        }
                        var items = Completions.at(
                            workspace, params.getTextDocument().getUri(), directiveOpt.get(), pos,
                            params.getPosition(), file.source());
                        span.detail("items", items.size());
                        return Either.forLeft(items);
                    });
            }
        });
    }
}
