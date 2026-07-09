package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R456 enforcer: a {@link FileSnapshot} taken off a live {@link WorkspaceFile}
 * stays walkable and internally consistent after the live file has edited its
 * byte array, swapped its tree, and eagerly {@code close()}d the previous tree,
 * which is exactly what {@code didChange} does on the dispatch thread while a
 * request handler walks a snapshot on a pool thread. These are deterministic
 * proxies for that race: they drive the same edit / swap / close sequence in a
 * fixed order rather than under real concurrency.
 *
 * <p>This test is a named guard, not just correct-by-construction code, because
 * R347 Slice 5's {@code didClose} {@code close()} lands on the same lifecycle;
 * if the clone-on-read ever regresses to handing out the live tree, test 1 fails
 * with a freed-arena error instead of the failure surfacing intermittently in
 * production.
 */
class FileSnapshotConcurrencyTest {

    private static final String ORIGINAL = "type Foo { x: Int }\n";

    /**
     * The enforcer: snapshot, then apply a range edit that renames the type and
     * eager-closes the pre-edit tree, then walk the snapshot. The walk must
     * succeed against the freed original's clone, and the extracted text plus
     * version must be the pre-edit generation, not the live file's.
     */
    @Test
    void snapshotSurvivesRangeEditAndEagerClose() {
        var file = new WorkspaceFile(1, ORIGINAL);
        FileSnapshot snap = WorkspaceFileTestSupport.snapshot(file);
        try {
            // didChange-driven range edit: "Foo" (bytes 5..8) -> "Renamed". This
            // reassigns source, swaps the tree, and closes the previous tree.
            file.applyEdit(2, 5, 8, new Point(0, 5), new Point(0, 8), "Renamed");
            assertThat(new String(file.source(), StandardCharsets.UTF_8)).startsWith("type Renamed");

            assertSnapshotStillReadsPreEditFoo(snap);
        } finally {
            snap.close();
        }
    }

    /** Same invariant, driven through the full-sync {@code replaceContent} path. */
    @Test
    void snapshotSurvivesReplaceContentAndEagerClose() {
        var file = new WorkspaceFile(1, ORIGINAL);
        FileSnapshot snap = WorkspaceFileTestSupport.snapshot(file);
        try {
            file.replaceContent(2, "type Renamed { y: String }\n");
            assertThat(new String(file.source(), StandardCharsets.UTF_8)).startsWith("type Renamed");

            assertSnapshotStillReadsPreEditFoo(snap);
        } finally {
            snap.close();
        }
    }

    private static void assertSnapshotStillReadsPreEditFoo(FileSnapshot snap) {
        Node root = snap.tree().getRootNode();
        assertThat(root.hasError()).isFalse();
        // Walk + extract text against the snapshot's own source: the pairing is the
        // pre-edit generation, so "Foo" is present and the live edit is invisible.
        Node name = DeclarationKind.findDefinition(root, snap.source(), "Foo").orElseThrow();
        assertThat(Nodes.text(name, snap.source())).isEqualTo("Foo");
        assertThat(DeclarationKind.findDefinition(root, snap.source(), "Renamed")).isEmpty();
        assertThat(snap.version()).isEqualTo(1);
    }

    /**
     * Closing the snapshot's clone must not disturb the live file, and editing
     * the live file (closing its own trees) must not disturb a still-open
     * snapshot: the two native lifetimes are independent in both directions.
     */
    @Test
    void snapshotCloseAndLiveFileAreIndependent() {
        var file = new WorkspaceFile(1, ORIGINAL);
        FileSnapshot snap = WorkspaceFileTestSupport.snapshot(file);

        // Close the snapshot first; the live file must keep parsing and editing.
        snap.close();
        file.applyEdit(2, 5, 8, new Point(0, 5), new Point(0, 8), "Bar");
        assertThat(file.tree().getRootNode().hasError()).isFalse();
        assertThat(new String(file.source(), StandardCharsets.UTF_8)).startsWith("type Bar");

        // A snapshot held across a further edit stays readable after that edit
        // closed the live file's intermediate tree.
        FileSnapshot held = WorkspaceFileTestSupport.snapshot(file);
        try {
            file.replaceContent(3, "type Baz { z: Int }\n");
            assertThat(Nodes.text(held.tree().getRootNode(), held.source())).contains("type Bar");
            assertThat(file.tree().getRootNode().hasError()).isFalse();
        } finally {
            held.close();
        }
    }

    /**
     * {@code withAllViews} captures one generation of every open file. A
     * {@code didChange} against one file after the views are taken (the lock is
     * released before the lambda runs, so the edit proceeds) must be invisible to
     * the already-captured views, and the other file's view is unaffected.
     */
    @Test
    void withAllViewsCapturesOneConsistentGeneration() {
        String uriA = "file:///a.graphqls";
        String uriB = "file:///b.graphqls";
        var ws = new Workspace();
        ws.didOpen(uriA, 1, "type A { x: Int }\n");
        ws.didOpen(uriB, 1, "type B { y: Int }\n");

        ws.withAllViews(views -> {
            FileSnapshot a = views.get(uriA);
            FileSnapshot b = views.get(uriB);
            String aBefore = new String(a.source(), StandardCharsets.UTF_8);

            // Edit A after the views were captured.
            ws.didChange(uriA, 2,
                List.of(new TextDocumentContentChangeEvent("type A { renamed: Int }\n")));

            // The captured views still reflect the pre-edit generation of both files.
            assertThat(new String(a.source(), StandardCharsets.UTF_8))
                .isEqualTo(aBefore)
                .contains("x: Int")
                .doesNotContain("renamed");
            assertThat(a.tree().getRootNode().hasError()).isFalse();
            assertThat(new String(b.source(), StandardCharsets.UTF_8)).contains("y: Int");
            return null;
        });
    }
}
