package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.catalog.CompletionData;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle and dependency-tracked recalculation queue, mirroring the
 * Rust LSP's {@code Workspace} test cases at the data-structure level
 * (no LSP framing yet; that's covered by {@code TextDocumentServiceTest}).
 */
class WorkspaceTest {

    @Test
    void didOpenAddsFileAndEnqueuesIt() {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }");

        assertThat(ws.get("file:///a.graphqls")).isPresent();
        assertThat(ws.drainRecalculate()).containsExactly("file:///a.graphqls");
    }

    @Test
    void didChangeFullSyncReplacesContent() {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }");
        ws.drainRecalculate();

        var change = new TextDocumentContentChangeEvent("type Foo { y: Int }");
        ws.didChange("file:///a.graphqls", 2, List.of(change));

        var file = ws.get("file:///a.graphqls").orElseThrow();
        assertThat(new String(file.source())).contains("y: Int");
        assertThat(file.version()).isEqualTo(2);
        assertThat(ws.drainRecalculate()).containsExactly("file:///a.graphqls");
    }

    @Test
    void didChangeIncrementalAppliesRange() {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }\n");
        ws.drainRecalculate();

        // Replace the type name "Foo" with "Bar" via a range edit.
        var range = new Range(new Position(0, 5), new Position(0, 8));
        var change = new TextDocumentContentChangeEvent(range, "Bar");
        ws.didChange("file:///a.graphqls", 2, List.of(change));

        var file = ws.get("file:///a.graphqls").orElseThrow();
        assertThat(new String(file.source())).startsWith("type Bar");
    }

    @Test
    void editingDeclaringFileEnqueuesDependents() {
        var ws = new Workspace();
        ws.didOpen("file:///decl.graphqls", 1, "type Foo { x: Int }\n");
        ws.didOpen("file:///dep.graphqls", 1, "type Bar { f: Foo }\n");
        ws.drainRecalculate();

        // Touch the declaring file: the depending file must show up in
        // the recalculation queue too.
        var change = new TextDocumentContentChangeEvent("type Foo { y: String }\n");
        ws.didChange("file:///decl.graphqls", 2, List.of(change));

        assertThat(ws.drainRecalculate())
            .containsExactlyInAnyOrder("file:///decl.graphqls", "file:///dep.graphqls");
    }

    @Test
    void editingNonDependedFileDoesNotEnqueueOthers() {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type A { x: Int }\n");
        ws.didOpen("file:///b.graphqls", 1, "type B { y: Int }\n");
        ws.drainRecalculate();

        // No FK between them; an edit to A should not pull in B.
        var change = new TextDocumentContentChangeEvent("type A { x: String }\n");
        ws.didChange("file:///a.graphqls", 2, List.of(change));

        assertThat(ws.drainRecalculate()).containsExactly("file:///a.graphqls");
    }

    @Test
    void didCloseRemovesFileAndEnqueuesDependents() {
        var ws = new Workspace();
        ws.didOpen("file:///decl.graphqls", 1, "type Foo { x: Int }\n");
        ws.didOpen("file:///dep.graphqls", 1, "type Bar { f: Foo }\n");
        ws.drainRecalculate();

        ws.didClose("file:///decl.graphqls");

        assertThat(ws.get("file:///decl.graphqls")).isEmpty();
        assertThat(ws.drainRecalculate())
            .containsExactlyInAnyOrder("file:///decl.graphqls", "file:///dep.graphqls");
    }

    @Test
    void setCatalogEnqueuesAllOpenFiles() {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type A { x: Int }\n");
        ws.didOpen("file:///b.graphqls", 1, "type B { y: Int }\n");
        ws.drainRecalculate();

        ws.setCatalog(CompletionData.empty());

        assertThat(ws.drainRecalculate())
            .containsExactlyInAnyOrder("file:///a.graphqls", "file:///b.graphqls");
    }
}
