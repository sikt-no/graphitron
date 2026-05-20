package no.sikt.graphitron.lsp;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
    void didChangeIncrementalAccountsForMultiByteUtf8() {
        // Norwegian table-name argument: 'h' starts at UTF-16 char 23,
        // but å expands to 2 UTF-8 bytes so byte and char offsets diverge
        // partway through. The range edit must convert correctly or it
        // will splice the source mid-codepoint.
        var ws = new Workspace();
        String original = "type Foo @table(name: \"håndtering\") { bar: Int }\n";
        ws.didOpen("file:///a.graphqls", 1, original);
        ws.drainRecalculate();

        // UTF-16 character offsets: opening quote at 22, 'h' at 23,
        // 'å' at 24 (still 1 UTF-16 unit), 'g' at 32, closing quote at 33.
        var range = new Range(new Position(0, 23), new Position(0, 33));
        var change = new TextDocumentContentChangeEvent(range, "FILM");
        ws.didChange("file:///a.graphqls", 2, List.of(change));

        var file = ws.get("file:///a.graphqls").orElseThrow();
        assertThat(new String(file.source(), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("type Foo @table(name: \"FILM\") { bar: Int }\n");
        // Tree must re-parse cleanly after a multi-byte edit.
        assertThat(file.tree().getRootNode().hasError()).isFalse();
    }

    @Test
    void editAfterMultiByteDescriptionPreservesDownstreamLines() {
        // Description on line 0 contains å (2 UTF-8 bytes). Line walking
        // counts \n separators only, so subsequent-line edits should be
        // unaffected by upstream multi-byte content.
        var ws = new Workspace();
        String original = """
            "Tabell for å håndtere åremål"
            type Foo @table(name: "OLD") {
                bar: Int
            }
            """;
        ws.didOpen("file:///a.graphqls", 1, original);
        ws.drainRecalculate();

        // Line 1: replace "OLD" with "FILM". UTF-16 columns map directly
        // because line 1 itself is ASCII; the line-walker had to advance
        // past the multi-byte description on line 0.
        var range = new Range(new Position(1, 23), new Position(1, 26));
        var change = new TextDocumentContentChangeEvent(range, "FILM");
        ws.didChange("file:///a.graphqls", 2, List.of(change));

        var file = ws.get("file:///a.graphqls").orElseThrow();
        assertThat(new String(file.source(), java.nio.charset.StandardCharsets.UTF_8))
            .contains("@table(name: \"FILM\")")
            .contains("Tabell for å håndtere åremål");
        assertThat(file.tree().getRootNode().hasError()).isFalse();
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
    void setBuildOutputEnqueuesAllOpenFiles() {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type A { x: Int }\n");
        ws.didOpen("file:///b.graphqls", 1, "type B { y: Int }\n");
        ws.drainRecalculate();

        ws.setBuildOutput(
            new GraphQLRewriteGenerator.BuildArtifacts(
                CompletionData.empty(),
                new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of())),
            ValidationReport.empty());

        assertThat(ws.drainRecalculate())
            .containsExactlyInAnyOrder("file:///a.graphqls", "file:///b.graphqls");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicQueueMutators")
    void everyPublicQueueMutatingMethodFiresTheListener(String name, Consumer<Workspace> mutator) {
        var ws = new Workspace();
        // Pre-seed: one open file, a Built.Current snapshot so demoteSnapshot
        // transitions (rather than no-ops) and setBuildOutput has a well-formed
        // BuildArtifacts to swap into.
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }\n");
        ws.setBuildOutput(
            new GraphQLRewriteGenerator.BuildArtifacts(
                CompletionData.empty(),
                new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of())),
            ValidationReport.empty());
        ws.drainRecalculate();
        var fires = new AtomicInteger();
        ws.setRecalculateListener(fires::incrementAndGet);

        mutator.accept(ws);

        assertThat(fires.get())
            .as("%s should fire the listener exactly once", name)
            .isEqualTo(1);
    }

    static Stream<Arguments> publicQueueMutators() {
        return Stream.of(
            Arguments.of("didOpen",
                (Consumer<Workspace>) ws -> ws.didOpen("file:///b.graphqls", 1, "type Bar { y: Int }\n")),
            Arguments.of("didChange",
                (Consumer<Workspace>) ws -> ws.didChange("file:///a.graphqls", 2,
                    List.of(new TextDocumentContentChangeEvent("type Foo { y: Int }\n")))),
            Arguments.of("didClose",
                (Consumer<Workspace>) ws -> ws.didClose("file:///a.graphqls")),
            Arguments.of("setBuildOutput",
                (Consumer<Workspace>) ws -> ws.setBuildOutput(
                    new GraphQLRewriteGenerator.BuildArtifacts(
                        CompletionData.empty(),
                        new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of())),
                    ValidationReport.empty())),
            Arguments.of("demoteSnapshot",
                (Consumer<Workspace>) Workspace::demoteSnapshot),
            Arguments.of("markAllForRecalculation",
                (Consumer<Workspace>) Workspace::markAllForRecalculation));
    }

    @Test
    void recalculateListenerDefaultsToNoOpForTestHarnesses() {
        var ws = new Workspace();
        // No setRecalculateListener call: the default Runnable should be a
        // no-op rather than null, so a mutator invocation does not NPE on the
        // listener field. Regression guard against a future implementation
        // that drops the no-op default.
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }\n");
        assertThat(ws.drainRecalculate()).containsExactly("file:///a.graphqls");
    }

    @Test
    void drainRecalculateIsIdempotentOnEmptyQueue() {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }\n");

        // First drain returns the queued entry; second returns empty. The
        // single-extraction property the listener path depends on: even if
        // the listener fires twice for two mutations interleaved with one
        // drain, the second drain only sees what was actually added since.
        assertThat(ws.drainRecalculate()).containsExactly("file:///a.graphqls");
        assertThat(ws.drainRecalculate()).isEmpty();
    }

    @ParameterizedTest(name = "no-op from {0}")
    @MethodSource("noOpDemoteStartingStates")
    void demoteSnapshotOnNoOpDoesNotFireListener(String name, LspSchemaSnapshot startingState) {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }\n");
        if (startingState instanceof LspSchemaSnapshot.Built.Previous) {
            // Drive the workspace through Current -> Previous via the public
            // path so the starting state is reached without reflection.
            ws.setBuildOutput(
                new GraphQLRewriteGenerator.BuildArtifacts(
                    CompletionData.empty(),
                    new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of())),
                ValidationReport.empty());
            ws.demoteSnapshot();
            assertThat(ws.snapshot()).isInstanceOf(LspSchemaSnapshot.Built.Previous.class);
        } else {
            assertThat(ws.snapshot()).isInstanceOf(LspSchemaSnapshot.Unavailable.class);
        }
        ws.drainRecalculate();
        var fires = new AtomicInteger();
        ws.setRecalculateListener(fires::incrementAndGet);

        ws.demoteSnapshot();

        assertThat(fires.get())
            .as("demoteSnapshot starting from %s should not fire the listener", name)
            .isZero();
    }

    static Stream<Arguments> noOpDemoteStartingStates() {
        return Stream.of(
            Arguments.of("Unavailable", new LspSchemaSnapshot.Unavailable()),
            Arguments.of("Built.Previous",
                new LspSchemaSnapshot.Built.Previous(List.of(), Map.of(), Map.of())));
    }

    @Test
    void setBuildOutputSwapsCatalogSnapshotAndReportAtomically() {
        var ws = new Workspace();
        assertThat(ws.catalog().tables()).isEmpty();
        assertThat(ws.snapshot()).isInstanceOf(LspSchemaSnapshot.Unavailable.class);
        assertThat(ws.validationReport().isEmpty()).isTrue();

        var catalog = CompletionData.empty();
        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of());
        var report = ValidationReport.from(java.util.List.of(),
            java.util.List.of(new no.sikt.graphitron.rewrite.BuildWarning(
                "shadowed directive",
                new graphql.language.SourceLocation(5, 3, "/x.graphqls"))));

        ws.setBuildOutput(new GraphQLRewriteGenerator.BuildArtifacts(catalog, snapshot), report);

        assertThat(ws.catalog()).isSameAs(catalog);
        assertThat(ws.snapshot()).isSameAs(snapshot);
        assertThat(ws.validationReport()).isSameAs(report);
    }
}
