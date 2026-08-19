package no.sikt.graphitron.lsp;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.lsp.state.FileSnapshot;
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
 * Lifecycle and the recalculation queue at the data-structure level (no LSP framing; that's
 * covered by {@code TextDocumentServiceTest}). The queue's subject is the capture cadence: an
 * open needs a first publish and a build changes every open file's answer, while an edit and a
 * close change neither.
 */
class WorkspaceTest {

    // Concrete-typed reads off the one open file's snapshot, scoped to the view
    // lambda (get() is gone). Concrete return types keep AssertJ's overloaded
    // assertThat unambiguous, which a generic <R> helper would not.

    private static String source(Workspace ws, String uri) {
        return ws.withView(uri, null, v -> new String(v.source(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int version(Workspace ws, String uri) {
        return ws.withView(uri, -1, FileSnapshot::version);
    }

    private static boolean hasParseError(Workspace ws, String uri) {
        return ws.withView(uri, false, v -> v.tree().getRootNode().hasError());
    }

    private static boolean isOpen(Workspace ws, String uri) {
        return ws.withView(uri, false, v -> true);
    }

    @Test
    void didOpenAddsFileAndEnqueuesIt() {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }");

        assertThat(isOpen(ws, "file:///a.graphqls")).isTrue();
        assertThat(ws.drainRecalculate()).containsExactly("file:///a.graphqls");
    }

    @Test
    void didChangeFullSyncReplacesContent() {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }");
        ws.drainRecalculate();

        var change = new TextDocumentContentChangeEvent("type Foo { y: Int }");
        ws.didChange("file:///a.graphqls", 2, List.of(change));

        assertThat(source(ws, "file:///a.graphqls")).contains("y: Int");
        assertThat(version(ws, "file:///a.graphqls")).isEqualTo(2);
        assertThat(ws.drainRecalculate())
            .as("an edit changes the buffer, not what the last capture said about it")
            .isEmpty();
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

        assertThat(source(ws, "file:///a.graphqls")).startsWith("type Bar");
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

        assertThat(source(ws, "file:///a.graphqls"))
            .isEqualTo("type Foo @table(name: \"FILM\") { bar: Int }\n");
        // Tree must re-parse cleanly after a multi-byte edit.
        assertThat(hasParseError(ws, "file:///a.graphqls")).isFalse();
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

        assertThat(source(ws, "file:///a.graphqls"))
            .contains("@table(name: \"FILM\")")
            .contains("Tabell for å håndtere åremål");
        assertThat(hasParseError(ws, "file:///a.graphqls")).isFalse();
    }

    @Test
    void editingADeclaringFileLeavesEveryOtherFileAlone() {
        var ws = new Workspace();
        ws.didOpen("file:///decl.graphqls", 1, "type Foo { x: Int }\n");
        ws.didOpen("file:///dep.graphqls", 1, "type Bar { f: Foo }\n");
        ws.drainRecalculate();

        // Renaming the declaration out from under the depending file is the strongest case the
        // cross-file fan-out was aimed at, and it enqueues nothing now: dep.graphqls shows what the
        // graph's last capture said about it, which an unsaved edit elsewhere has not changed.
        var change = new TextDocumentContentChangeEvent("type Renamed { y: String }\n");
        ws.didChange("file:///decl.graphqls", 2, List.of(change));

        assertThat(ws.drainRecalculate()).isEmpty();
    }

    @Test
    void didCloseRemovesTheFileAndEnqueuesNothing() {
        var ws = new Workspace();
        ws.didOpen("file:///decl.graphqls", 1, "type Foo { x: Int }\n");
        ws.didOpen("file:///dep.graphqls", 1, "type Bar { f: Foo }\n");
        ws.drainRecalculate();

        ws.didClose("file:///decl.graphqls");

        assertThat(isOpen(ws, "file:///decl.graphqls")).isFalse();
        // The closed file's own diagnostics are cleared by the document service, directly; no other
        // file's judgement was resting on this buffer being open.
        assertThat(ws.drainRecalculate()).isEmpty();
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
                new LspSchemaSnapshot.Built()));

        assertThat(ws.drainRecalculate())
            .containsExactlyInAnyOrder("file:///a.graphqls", "file:///b.graphqls");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicQueueMutators")
    void everyQueueMutatingMethodFiresTheListener(String name, Consumer<Workspace> mutator) {
        var ws = new Workspace();
        // Pre-seed: one open file, and a build behind the session so setBuildOutput has a
        // well-formed BuildArtifacts to swap into.
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }\n");
        ws.setBuildOutput(
            new GraphQLRewriteGenerator.BuildArtifacts(
                CompletionData.empty(),
                new LspSchemaSnapshot.Built()));
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
            Arguments.of("setBuildOutput",
                (Consumer<Workspace>) ws -> ws.setBuildOutput(
                    new GraphQLRewriteGenerator.BuildArtifacts(
                        CompletionData.empty(),
                        new LspSchemaSnapshot.Built()))),
            Arguments.of("markAllForRecalculation",
                (Consumer<Workspace>) Workspace::markAllForRecalculation));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bufferOnlyMutators")
    void aMutatorThatChangesNoAnswerDoesNotFireTheListener(String name, Consumer<Workspace> mutator) {
        var ws = new Workspace();
        ws.didOpen("file:///a.graphqls", 1, "type Foo { x: Int }\n");
        ws.drainRecalculate();
        var fires = new AtomicInteger();
        ws.setRecalculateListener(fires::incrementAndGet);

        mutator.accept(ws);

        assertThat(fires.get())
            .as("%s changes a buffer, not what the store says, so nothing republishes", name)
            .isZero();
    }

    static Stream<Arguments> bufferOnlyMutators() {
        return Stream.of(
            Arguments.of("didChange",
                (Consumer<Workspace>) ws -> ws.didChange("file:///a.graphqls", 2,
                    List.of(new TextDocumentContentChangeEvent("type Foo { y: Int }\n")))),
            Arguments.of("didClose",
                (Consumer<Workspace>) ws -> ws.didClose("file:///a.graphqls")));
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

    @Test
    void setBuildOutputSwapsTheSnapshotIn() {
        var ws = new Workspace();
        assertThat(ws.snapshot()).isInstanceOf(LspSchemaSnapshot.Unavailable.class);

        var snapshot = new LspSchemaSnapshot.Built();

        ws.setBuildOutput(new GraphQLRewriteGenerator.BuildArtifacts(CompletionData.empty(), snapshot));

        assertThat(ws.snapshot()).isSameAs(snapshot);
    }
}
