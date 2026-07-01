package no.sikt.graphitron.lsp.state;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Positions;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-aggregator state: the set of open schema files plus the catalog the
 * LSP queries against. Mirrors the Rust LSP's {@code state/workspace.rs}
 * {@code Workspace} struct.
 *
 * <p>Thread-safe: lsp4j dispatches notifications and requests on a worker
 * pool; mutating operations and the recalculation queue are serialised
 * through {@code lock}. The catalog reference is {@code volatile} so a
 * catalog-refresh swap (driven by the {@code .class}-watcher in
 * {@code DevMojo}) is observable on the next request without taking the
 * file lock.
 *
 * <p>A {@code did_change} (or {@code did_open} / {@code did_close})
 * enqueues the touched file plus any other file whose
 * {@code dependsOnDeclarations} overlaps the touched file's
 * {@code declaredTypes}, before or after the change. Diagnostic runs
 * drain the queue.
 */
public final class Workspace {

    private final Object lock = new Object();
    private final Map<String, WorkspaceFile> files = new LinkedHashMap<>();
    private final List<String> toRecalculate = new ArrayList<>();
    private final LspVocabulary vocabulary;
    private volatile CompletionData catalog;
    // R362 — the catalog-discovery projection the MCP catalog.* tools read. Swapped alongside the
    // catalog and snapshot in setBuildOutput, so a single set of volatile reads observes one
    // consistent build state. Defaults to empty until the first build.
    private volatile CatalogFacts catalogFacts = CatalogFacts.empty();
    // The LSP is the sole source walker (R352): the walker (and its per-file
    // cache) lives here, alongside the index it produces, so "who refreshes this,
    // on what cadence" is answerable from the index's owner. There is no
    // process-wide static cache shared with the generator build cadence.
    private final SourceWalker sourceWalker = new SourceWalker();
    private volatile SourceWalker.Index sourceIndex = SourceWalker.Index.EMPTY;
    private volatile LspSchemaSnapshot snapshot = LspSchemaSnapshot.unavailable();
    private volatile ValidationReport validationReport = ValidationReport.empty();
    private volatile InlayHintConfig inlayHintConfig = InlayHintConfig.defaults();
    private volatile Runnable recalculateListener = () -> {};

    public Workspace() {
        this(CompletionData.empty(), LspVocabulary.load());
    }

    public Workspace(CompletionData catalog) {
        this(catalog, LspVocabulary.load());
    }

    public Workspace(CompletionData catalog, LspVocabulary vocabulary) {
        this.catalog = catalog;
        this.vocabulary = vocabulary;
    }

    public void didOpen(String uri, int version, String text) {
        enqueueAndNotify(() -> {
            var file = new WorkspaceFile(version, text);
            files.put(uri, file);
            enqueueTouched(uri, Set.of(), file.declaredTypes());
        });
    }

    public void didChange(String uri, int newVersion, List<TextDocumentContentChangeEvent> changes) {
        enqueueAndNotify(() -> {
            var file = files.get(uri);
            if (file == null) {
                return;
            }
            var declaredBefore = file.declaredTypes();
            for (var change : changes) {
                applyChange(file, newVersion, change);
            }
            enqueueTouched(uri, declaredBefore, file.declaredTypes());
        });
    }

    public void didClose(String uri) {
        enqueueAndNotify(() -> {
            var file = files.remove(uri);
            if (file == null) {
                return;
            }
            enqueueTouched(uri, file.declaredTypes(), Set.of());
        });
    }

    public Optional<WorkspaceFile> get(String uri) {
        synchronized (lock) {
            return Optional.ofNullable(files.get(uri));
        }
    }

    /**
     * Snapshot of every open file URI in registration order. Used by
     * the workspace-scoped code-action provider to compose
     * cross-document {@link org.eclipse.lsp4j.WorkspaceEdit}s.
     */
    public List<String> openUris() {
        synchronized (lock) {
            return List.copyOf(files.keySet());
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

    public CompletionData catalog() {
        return catalog;
    }

    /**
     * R362 — the catalog-discovery projection the MCP {@code catalog.tables} / {@code catalog.describe}
     * tools read off the live handle on every call. Refreshes on the catalog (classpath) build
     * cadence through {@link #setBuildOutput}, the same swap the catalog and snapshot ride. Stays
     * {@link CatalogFacts#empty()} until the first successful build. {@code volatile} so the swap is
     * observable on the next request without taking the file lock, mirroring {@link #catalog}.
     */
    public CatalogFacts catalogFacts() {
        return catalogFacts;
    }

    /**
     * The LSP-owned source-position index goto-definition joins service-half
     * class / method references against. Distinct from {@link #catalog()}: it
     * refreshes on the {@code .java} (source) cadence through
     * {@link #setSourceIndex}, driven by the dev goal's source-root watcher,
     * not on the generator / {@code .class} build cadence the catalog rides.
     * That decoupling is the point of R349: a declaration position becomes
     * available the instant its source is parsed, without waiting for a catalog
     * rebuild. {@code volatile} so the swap is observable on the next request
     * without taking the file lock, mirroring {@link #catalog}.
     */
    public SourceWalker.Index sourceIndex() {
        return sourceIndex;
    }

    /**
     * Atomic swap of the source-position index. Called by the dev goal on
     * startup (initial walk) and from the source-root watcher on every
     * {@code .java} change. Independent of {@link #setBuildOutput}: a source
     * edit refreshes positions without touching the catalog, snapshot, or
     * validator report, and does not enqueue a diagnostic recalculation
     * (positions feed goto-definition, not diagnostics).
     */
    public void setSourceIndex(SourceWalker.Index index) {
        this.sourceIndex = index == null ? SourceWalker.Index.EMPTY : index;
    }

    /**
     * Walks {@code sourceRoots} with the workspace-owned {@link SourceWalker}
     * (warm per-file cache) and atomically swaps in the resulting index. This is
     * the only walk the LSP performs: the dev goal's source-root watcher calls it
     * at startup and on every {@code .java} change, on the source cadence,
     * independent of the catalog / {@code .class} build cadence. Re-parses only
     * the files whose modification time changed since the previous refresh.
     */
    public void refreshSourceIndex(List<Path> sourceRoots) {
        setSourceIndex(sourceWalker.walk(sourceRoots));
    }

    /**
     * Projection of the parsed user schema's directive surface, swapped on
     * every successful generator pass through {@link #setBuildOutput}. Stays
     * {@link LspSchemaSnapshot.Unavailable} until the first build succeeds;
     * demotes to {@link LspSchemaSnapshot.Built.Previous} on subsequent
     * parse failures so consumers can distinguish fresh-vs-stale info.
     */
    public LspSchemaSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Validator output paired with the catalog and snapshot, swapped on
     * every successful generator pass through {@link #setBuildOutput}. Stays
     * {@link ValidationReport#empty()} until the first build completes;
     * unaffected by {@link #demoteSnapshot()} so a stale-snapshot state
     * still has the last good validator output sitting there ready to
     * re-publish on revert.
     */
    public ValidationReport validationReport() {
        return validationReport;
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
     * R160 — the client's inlay-hint / hover toggles. Read on every inlay-hint and hover
     * request; swapped atomically by {@link #setInlayHintConfig} when the document service
     * receives a {@code workspace/didChangeConfiguration} notification (or pulls fresh
     * settings via {@code workspace/configuration}). Stays at {@link InlayHintConfig#defaults()}
     * (all off) until the client opts in, which is the no-behaviour-change-for-existing-users
     * contract the spec requires.
     */
    public InlayHintConfig inlayHintConfig() {
        return inlayHintConfig;
    }

    /**
     * R160 — atomic swap of the client's inlay-hint / hover toggles. Called by the
     * document service from the configuration-pull path on initialisation and from the
     * {@code workspace/didChangeConfiguration} notification handler.
     */
    public void setInlayHintConfig(InlayHintConfig config) {
        this.inlayHintConfig = config == null ? InlayHintConfig.defaults() : config;
    }

    /**
     * Success-path swap: catalog, snapshot, and validator report move
     * together atomically (from the perspective of consumers that hold the
     * workspace through three volatile reads), one recalculation. Used by
     * both the schema-save trigger (where the validator runs against the
     * freshly parsed user schema) and the classpath trigger (where the
     * validator's classpath-dependent rejections — unresolved {@code @service}
     * class, etc. — surface on the next {@code mvn compile} without waiting
     * for a schema save). The producer ships only
     * {@link LspSchemaSnapshot.Built.Current}; freshness demotion happens
     * through {@link #demoteSnapshot()} when a later parse fails.
     */
    public void setBuildOutput(GraphQLRewriteGenerator.BuildArtifacts artifacts, ValidationReport report) {
        this.catalog = artifacts.catalog();
        this.snapshot = artifacts.snapshot();
        this.catalogFacts = artifacts.catalogFacts();
        this.validationReport = report;
        markAllForRecalculation();
    }

    /**
     * Failure path: the latest parse threw, but a prior parse had succeeded.
     * Demote {@link LspSchemaSnapshot.Built.Current} to
     * {@link LspSchemaSnapshot.Built.Previous} so consumers see "stale"
     * rather than "fresh". No-op on {@link LspSchemaSnapshot.Unavailable}
     * (no prior success to demote) and on {@link LspSchemaSnapshot.Built.Previous}
     * (already stale).
     */
    public void demoteSnapshot() {
        var current = this.snapshot;
        if (current instanceof LspSchemaSnapshot.Built.Current c) {
            this.snapshot = new LspSchemaSnapshot.Built.Previous(
                c.directives(), c.typesByName(), c.payloadDataFieldByType(),
                c.fieldClassificationsByCoord(), c.typeClassificationsByName(),
                c.typeDefinitionLocations());
            markAllForRecalculation();
        }
    }

    /**
     * Convenience wrapper around
     * {@link DirectiveResolution#resolve(LspVocabulary, LspSchemaSnapshot, String)}
     * for request callbacks that already hold a {@link Workspace}. Reads the
     * snapshot ref through the volatile field.
     */
    public DirectiveResolution resolveDirective(String name) {
        return DirectiveResolution.resolve(vocabulary, snapshot, name);
    }

    /**
     * Enqueue every open file for diagnostic recalculation. Used by the
     * dev goal when the generator runs (the source tree changed even
     * though no individual buffer did) and internally on catalog swaps.
     */
    public void markAllForRecalculation() {
        enqueueAndNotify(() -> {
            for (var uri : files.keySet()) {
                if (!toRecalculate.contains(uri)) {
                    toRecalculate.add(uri);
                }
            }
        });
    }

    /**
     * Single-slot listener wire-up. The listener fires once after every
     * public mutator that touches {@code toRecalculate} returns, off the
     * workspace {@code lock}. Default is no-op so test callers that drive
     * the workspace directly need no setup; the document service installs
     * the real publish callback from
     * {@link no.sikt.graphitron.lsp.server.GraphitronTextDocumentService#setClient}.
     *
     * <p>One slot, not a list: today there is exactly one consumer (the
     * document service drains the queue and ships diagnostics). Lift to a
     * multi-consumer fan-out when a second consumer surfaces with a
     * forcing function.
     */
    public void setRecalculateListener(Runnable listener) {
        this.recalculateListener = listener;
    }

    /**
     * Funnel for every {@code toRecalculate} write reachable from the six
     * public mutators ({@link #didOpen}, {@link #didChange},
     * {@link #didClose}, {@link #setBuildOutput}, {@link #demoteSnapshot},
     * {@link #markAllForRecalculation}). The mutation runs under
     * {@code lock} so the queue stays consistent with the file map; the
     * listener fires after lock release so a heavy
     * {@code publishDiagnosticsForRecalculate} on the lsp4j thread does
     * not serialise build swaps on the watcher thread behind it.
     * Idempotency on the drain side (a second {@link #drainRecalculate}
     * after the first returns empty) makes any "listener fires twice for
     * two mutations interleaved with one drain" race a no-op rather than
     * a correctness hazard.
     */
    private void enqueueAndNotify(Runnable mutation) {
        synchronized (lock) {
            mutation.run();
        }
        recalculateListener.run();
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

    private void enqueueTouched(String uri, Set<String> declaredBefore, Set<String> declaredAfter) {
        if (!toRecalculate.contains(uri)) {
            toRecalculate.add(uri);
        }
        var changedDecls = new LinkedHashSet<String>();
        changedDecls.addAll(declaredBefore);
        changedDecls.addAll(declaredAfter);
        if (changedDecls.isEmpty()) {
            return;
        }
        for (var entry : files.entrySet()) {
            String otherUri = entry.getKey();
            if (otherUri.equals(uri)) {
                continue;
            }
            if (intersects(entry.getValue().dependsOnDeclarations(), changedDecls)
                && !toRecalculate.contains(otherUri)) {
                toRecalculate.add(otherUri);
            }
        }
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        var smaller = a.size() < b.size() ? a : b;
        var larger = smaller == a ? b : a;
        for (var s : smaller) {
            if (larger.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
