package no.sikt.graphitron.lsp.state;

import no.sikt.graphitron.lsp.catalog.CompletionData;
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
 * Phase 1 catalog-refresh swap (driven by the {@code .class}-watcher in
 * {@code DevMojo}) is observable on the next request without taking the
 * file lock.
 *
 * <p>A {@code did_change} (or {@code did_open} / {@code did_close})
 * enqueues the touched file plus any other file whose
 * {@code dependsOnDeclarations} overlaps the touched file's
 * {@code declaredTypes}, before or after the change. Diagnostic runs
 * drain the queue. Phase 1 only fills the queue; Phase 3 starts draining
 * it for diagnostics.
 */
public final class Workspace {

    private final Object lock = new Object();
    private final Map<String, WorkspaceFile> files = new LinkedHashMap<>();
    private final List<String> toRecalculate = new ArrayList<>();
    private volatile CompletionData catalog;

    public Workspace() {
        this(CompletionData.empty());
    }

    public Workspace(CompletionData catalog) {
        this.catalog = catalog;
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
     * Drain the recalculation queue. Returned URIs are the files whose
     * diagnostics must be recomputed. Phase 1 wires this; Phase 3 drives
     * it from the diagnostic scheduler.
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

    public void setCatalog(CompletionData catalog) {
        this.catalog = catalog;
        markAllForRecalculation();
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
