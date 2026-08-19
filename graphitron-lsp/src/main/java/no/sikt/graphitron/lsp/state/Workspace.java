package no.sikt.graphitron.lsp.state;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.trace.LspTrace;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Per-aggregator state: the set of open schema files plus the session's read
 * access to the facts the LSP queries against. Mirrors the Rust LSP's
 * {@code state/workspace.rs} {@code Workspace} struct.
 *
 * <p>Thread-safe: lsp4j dispatches notifications and requests on a worker
 * pool; mutating operations and the recalculation queue are serialised
 * through {@code lock}. Each per-round reference is {@code volatile} so a
 * build-output swap (driven by the {@code .class}-watcher in
 * {@code DevMojo}) is observable on the next request without taking the
 * file lock.
 *
 * <p>Diagnostics ride the capture cadence rather than the keystroke, so the
 * queue fills from two events only: a file being opened, which has nothing
 * published for it yet, and a build swapping what the store says, which
 * changes the answer for every open file at once. An edit enqueues nothing.
 * What a buffer shows between captures is the last capture's judgement of it,
 * which is the same judgement every other open file is showing.
 */
public final class Workspace {

    private final Object lock = new Object();
    private final Map<String, WorkspaceFile> files = new LinkedHashMap<>();
    private final List<String> toRecalculate = new ArrayList<>();
    private final LspVocabulary vocabulary;
    private volatile LspSchemaSnapshot snapshot = LspSchemaSnapshot.unavailable();
    private volatile InlayHintConfig inlayHintConfig = InlayHintConfig.defaults();
    private volatile Runnable recalculateListener = () -> {};
    // The session's read access to the fact store, set once by whoever started the session and
    // null when nobody did. Not a projection swapped per round like the fields above: the store
    // is written by capture on its own cadence and read live, so there is nothing here to
    // refresh. A session without one answers store-backed requests absent, which is what a bare
    // Launcher outside a build has always done.
    private volatile StoreAccess store;

    public Workspace() {
        this(LspVocabulary.load());
    }

    public Workspace(LspVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    public void didOpen(String uri, int version, String text) {
        enqueueAndNotify(() -> {
            files.put(uri, new WorkspaceFile(version, text));
            enqueue(uri);
        });
    }

    /**
     * Applies the editor's edits to the buffer. Enqueues nothing: what the store says about this
     * document is what it said at the last capture, and typing does not change that. The diagnostics
     * already published stand until the next capture republishes them against the buffer as it then
     * reads.
     */
    public void didChange(String uri, int newVersion, List<TextDocumentContentChangeEvent> changes) {
        mutate(() -> {
            var file = files.get(uri);
            if (file == null) {
                return;
            }
            for (var change : changes) {
                applyChange(file, newVersion, change);
            }
        });
    }

    /**
     * Drops the buffer. Enqueues nothing: the closed document's own diagnostics are cleared by the
     * document service, and no other document's judgement depended on this one being open.
     */
    public void didClose(String uri) {
        mutate(() -> files.remove(uri));
    }

    /**
     * Run {@code present} against an immutable {@link FileSnapshot} of the one
     * open file at {@code uri}, or return {@code absent} if no such file is open.
     *
     * <p>The snapshot is taken under {@code lock} (so its {@code (tree, source,
     * version)} triple is one consistent generation and gets the happens-before
     * edge against the {@code didChange} mutators), the lock is released before
     * {@code present} runs (so a slow feature computation does not serialise
     * edits behind it), and the cloned tree is closed in a {@code finally}. The
     * live {@link WorkspaceFile} never escapes: callers can only read the
     * dispatch-thread-safe snapshot, and only for the duration of the lambda, so
     * a clone can neither be leaked nor read after close.
     */
    public <R> R withView(String uri, R absent, java.util.function.Function<FileSnapshot, R> present) {
        FileSnapshot view;
        // Scoped to the lock-held region only, not to `present`: this span's duration is
        // how long a read request waited on the mutator lock, which is the contention a
        // slow inline recalculation inflicts on concurrent requests. The feature
        // computation that follows is timed by the calling handler's own span.
        try (var _ = LspTrace.span("workspace.snapshot")) {
            synchronized (lock) {
                var file = files.get(uri);
                if (file == null) {
                    return absent;
                }
                view = file.snapshot();
            }
        }
        try {
            return present.apply(view);
        } finally {
            view.close();
        }
    }

    /**
     * Run {@code present} against an immutable {@link FileSnapshot} of every open
     * file, keyed by URI in registration order, all captured under one lock
     * acquisition so a composed cross-document {@link org.eclipse.lsp4j.WorkspaceEdit}
     * is computed against a single consistent generation of the whole workspace
     * (never a per-file mix). Every clone is closed in a {@code finally}, including
     * any taken before {@code present} threw. Used by the cross-document code-action
     * and goto-definition paths.
     */
    public <R> R withAllViews(java.util.function.Function<Map<String, FileSnapshot>, R> present) {
        var views = new LinkedHashMap<String, FileSnapshot>();
        try {
            // Lock-held region only, as in withView. Clones every open file, so on a large
            // workspace this is the request-path cost that scales with file count.
            try (var span = LspTrace.span("workspace.snapshotAll")) {
                synchronized (lock) {
                    for (var entry : files.entrySet()) {
                        views.put(entry.getKey(), entry.getValue().snapshot());
                    }
                }
                span.detail("files", views.size());
            }
            return present.apply(views);
        } finally {
            for (var view : views.values()) {
                view.close();
            }
        }
    }

    /**
     * Drain the recalculation queue. Returned URIs are the files whose
     * diagnostics must be recomputed.
     */
    public List<String> drainRecalculate() {
        synchronized (lock) {
            var copy = List.copyOf(toRecalculate);
            toRecalculate.clear();
            return copy;
        }
    }

    /**
     * Hands this session its read access to the fact store, and takes over closing it. Called once
     * by whoever started the session and holds the store the session writes through.
     */
    public void setStore(StoreAccess store) {
        this.store = store;
    }

    /**
     * Answers a request about the document at {@code uri} from the store, inside one read
     * transaction and scoped to the graph that document belongs to. {@code answer} receives an empty
     * handle when there is no store, when the URI names no file on disk, and when no graph of this
     * session's has read that file: three different absences with one shape, because a handler's
     * response to all three is the same and telling them apart at a completion site would be
     * inventing a distinction the author cannot see.
     *
     * <p>This is the only door to the store. Nothing hands out a query surface that outlives the
     * call, since a handle used after its transaction has ended is a read that can tear against a
     * capture.
     */
    public <R> R answering(String uri, Function<Optional<StoreHandle>, R> answer) {
        StoreAccess access = store;
        Optional<String> sourceName = StoreAccess.sourceNameOf(uri);
        if (access == null || sourceName.isEmpty()) {
            return answer.apply(Optional.empty());
        }
        return access.answering(sourceName.get(), answer);
    }

    /**
     * Answers a request about several documents at once, inside one read transaction, each scoped to the
     * graph its own document belongs to. The lookup is keyed on URI as {@link #answering} is, and a URI
     * this session's graphs have nothing to say about resolves to an empty handle rather than being
     * absent, so a caller reads every URI it asked about out of the same lookup.
     *
     * <p>The door a whole recalculation goes through. Per-document calls open a transaction and resolve
     * a membership each, so a drain of forty files paid eighty statements before reading a fact; this
     * pays one membership resolution for the set and lets the caller read the facts in one go.
     */
    public <R> R answeringAll(
        Collection<String> uris, Function<Function<String, Optional<StoreHandle>>, R> answer
    ) {
        StoreAccess access = store;
        if (access == null) {
            return answer.apply(uri -> Optional.empty());
        }
        var sourceNames = new LinkedHashMap<String, String>();
        for (String uri : uris) {
            StoreAccess.sourceNameOf(uri).ifPresent(sourceName -> sourceNames.put(uri, sourceName));
        }
        return access.answeringAll(sourceNames.values(), handles -> answer.apply(uri -> {
            String sourceName = sourceNames.get(uri);
            return sourceName == null ? Optional.empty() : handles.of(sourceName);
        }));
    }

    /** Releases the session's store access, if it was given one. Idempotent. */
    public void closeStore() {
        StoreAccess access = store;
        store = null;
        if (access != null) {
            access.close();
        }
    }

    /**
     * The classifier's projection of the last build, swapped on every successful generator pass
     * through {@link #setBuildOutput}. Stays {@link LspSchemaSnapshot.Unavailable} until the first
     * build succeeds and holds the last successful projection after that: a failed pass leaves it
     * alone rather than marking it stale, staleness having had no reader left once the build's own
     * findings moved to the store.
     */
    public LspSchemaSnapshot snapshot() {
        return snapshot;
    }

    /**
     * The LSP's directive vocabulary, parsed once at startup from the
     * bundled {@code directives.graphqls} and immutable thereafter. The
     * registry is shape, not state; there is no setter.
     */
    public LspVocabulary vocabulary() {
        return vocabulary;
    }

    /**
     * The client's inlay-hint / hover toggles. Read on every inlay-hint and hover
     * request; swapped atomically by {@link #setInlayHintConfig} when the document service
     * receives a {@code workspace/didChangeConfiguration} notification (or pulls fresh
     * settings via {@code workspace/configuration}). Stays at {@link InlayHintConfig#defaults()}
     * (all off) until the client opts in, so a client that never configures the toggles sees no
     * behaviour change.
     */
    public InlayHintConfig inlayHintConfig() {
        return inlayHintConfig;
    }

    /**
     * Atomic swap of the client's inlay-hint / hover toggles. Called by the
     * document service from the configuration-pull path on initialisation and from the
     * {@code workspace/didChangeConfiguration} notification handler.
     */
    public void setInlayHintConfig(InlayHintConfig config) {
        this.inlayHintConfig = config == null ? InlayHintConfig.defaults() : config;
    }

    /**
     * Success-path swap, one recalculation. Used by both the schema-save trigger and the classpath
     * trigger, the latter so a build's classpath-dependent verdicts surface on the next
     * {@code mvn compile} without waiting for a schema save. A failed pass calls nothing here: what
     * it has to say about the schema it wrote to the store, and this projection stays as the last
     * successful pass left it.
     *
     * <p>The build's own errors and warnings do not ride along: capture writes them to the store's
     * diagnostics stratum on the same round, and the language server reads them there. The artifacts
     * also carry the build's {@code CompletionData} catalog, which no language-server surface reads
     * any more; the dev goal logs its census counts from its own copy.
     */
    public void setBuildOutput(GraphQLRewriteGenerator.BuildArtifacts artifacts) {
        this.snapshot = artifacts.snapshot();
        markAllForRecalculation();
    }

    /**
     * Enqueue every open file for diagnostic recalculation. Used by the
     * dev goal when the generator runs (the source tree changed even
     * though no individual buffer did) and internally on catalog swaps.
     */
    public void markAllForRecalculation() {
        enqueueAndNotify(() -> files.keySet().forEach(this::enqueue));
    }

    /**
     * Single-slot listener wire-up. The listener fires once after every
     * public mutator that touches {@code toRecalculate} returns, off the
     * workspace {@code lock}. Default is no-op so test callers that drive
     * the workspace directly need no setup; the document service installs
     * the real publish callback from
     * {@link no.sikt.graphitron.lsp.server.GraphitronTextDocumentService#setClient}.
     *
     * <p>One slot, not a list: there is exactly one consumer (the document
     * service drains the queue and ships diagnostics).
     */
    public void setRecalculateListener(Runnable listener) {
        this.recalculateListener = listener;
    }

    /**
     * Funnel for every {@code toRecalculate} write reachable from the three
     * public mutators that touch the queue ({@link #didOpen},
     * {@link #setBuildOutput}, {@link #markAllForRecalculation}). The
     * mutation runs under {@code lock} so the queue stays consistent with the
     * file map; the listener fires after lock release so a heavy
     * {@code publishDiagnosticsForRecalculate} on the lsp4j thread does
     * not serialise build swaps on the watcher thread behind it.
     * Idempotency on the drain side (a second {@link #drainRecalculate}
     * after the first returns empty) makes any "listener fires twice for
     * two mutations interleaved with one drain" race a no-op rather than
     * a correctness hazard.
     */
    private void enqueueAndNotify(Runnable mutation) {
        // Two spans, because the split is the whole question when the server stops
        // responding: `mutate` covers lock acquisition plus the mutation (so its duration
        // includes any wait behind a concurrent mutator), while `notify` covers the
        // listener, which drains the queue and computes diagnostics inline on this thread.
        // A `notify` far larger than its `mutate` sibling is the signal that a mutation's
        // real cost is the recalculation it triggers, not the edit itself.
        mutate(mutation);
        try (var _ = LspTrace.span("workspace.notify")) {
            recalculateListener.run();
        }
    }

    /**
     * The lock-held half, for the mutators that change a buffer without changing what has to be
     * published: {@link #didChange} and {@link #didClose}.
     */
    private void mutate(Runnable mutation) {
        try (var span = LspTrace.span("workspace.mutate")) {
            synchronized (lock) {
                mutation.run();
                span.detail("open", files.size()).detail("queued", toRecalculate.size());
            }
        }
    }

    private void enqueue(String uri) {
        if (!toRecalculate.contains(uri)) {
            toRecalculate.add(uri);
        }
    }

    private void applyChange(WorkspaceFile file, int newVersion, TextDocumentContentChangeEvent change) {
        var range = change.getRange();
        if (range == null) {
            file.replaceContent(newVersion, change.getText());
            return;
        }
        var start = Positions.resolve(file.source(), range.getStart().getLine(), range.getStart().getCharacter());
        var end = Positions.resolve(file.source(), range.getEnd().getLine(), range.getEnd().getCharacter());
        file.applyEdit(newVersion, start.byteOffset(), end.byteOffset(),
            start.tsPoint(), end.tsPoint(), change.getText());
    }

}
