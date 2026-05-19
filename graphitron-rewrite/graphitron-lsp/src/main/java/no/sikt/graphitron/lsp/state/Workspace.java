package no.sikt.graphitron.lsp.state;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Positions;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

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
    private volatile LspSchemaSnapshot snapshot = LspSchemaSnapshot.unavailable();
    private volatile ValidationReport validationReport = ValidationReport.empty();

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
        synchronized (lock) {
            var file = new WorkspaceFile(version, text);
            files.put(uri, file);
            enqueueTouched(uri, Set.of(), file.declaredTypes());
        }
    }

    public void didChange(String uri, int newVersion, List<TextDocumentContentChangeEvent> changes) {
        synchronized (lock) {
            var file = files.get(uri);
            if (file == null) {
                return;
            }
            var declaredBefore = file.declaredTypes();
            for (var change : changes) {
                applyChange(file, newVersion, change);
            }
            enqueueTouched(uri, declaredBefore, file.declaredTypes());
        }
    }

    public void didClose(String uri) {
        synchronized (lock) {
            var file = files.remove(uri);
            if (file == null) {
                return;
            }
            enqueueTouched(uri, file.declaredTypes(), Set.of());
        }
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
                c.directives(), c.typesByName(), c.payloadDataFieldByType());
            markAllForRecalculation();
        }
    }

    /**
     * Convenience wrapper around
     * {@link DirectiveResolution#resolve(LspVocabulary, LspSchemaSnapshot, String)}
     * for request callbacks that already hold a {@link Workspace}. Reads the
     * snapshot ref through the volatile field.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "snapshot-directive-roundtrip-faithful",
        reliesOn = "returns the snapshot's DirectiveShape when bundled SDL lacks the name; "
            + "callers (hover, arg-validation, the unknown-directive arm) trust the projected "
            + "args / description to round-trip the producer's registry without loss."
    )
    public DirectiveResolution resolveDirective(String name) {
        return DirectiveResolution.resolve(vocabulary, snapshot, name);
    }

    /**
     * Enqueue every open file for diagnostic recalculation. Used by the
     * dev goal when the generator runs (the source tree changed even
     * though no individual buffer did) and internally on catalog swaps.
     */
    public void markAllForRecalculation() {
        synchronized (lock) {
            for (var uri : files.keySet()) {
                if (!toRecalculate.contains(uri)) {
                    toRecalculate.add(uri);
                }
            }
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
