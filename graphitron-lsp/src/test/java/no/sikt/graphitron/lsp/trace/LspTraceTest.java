package no.sikt.graphitron.lsp.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of the trace seam itself. Two of these assertions are what make the seam safe
 * to leave sitting on per-keystroke paths and in the stdio deployment: off allocates no span
 * and emits nothing, and the default sink is never {@code System.out}.
 *
 * <p>Several of the rest exist because the configuration they cover is otherwise
 * unassertable. The {@code SLOW} threshold, the file sink and the {@code GRAPHITRON_LSP_TRACE}
 * arm are all resolved once at class initialisation in production, so they are reached here
 * through the package-private seams ({@code slowMsForTesting}, {@code openSink},
 * {@code enabledFrom}) rather than by mutating the JVM's properties or environment.
 *
 * <p>{@code @Isolated} because its subject is process-global rather than a fixture: {@link LspTrace}
 * holds its enabled flag and its sink in static fields, and this class rebinds both. With test
 * classes running concurrently that cuts both ways. While the flag is on here, any sibling that
 * parses a buffer, mutates a workspace or computes diagnostics opens spans that land in this
 * class's capture; and {@link #resetSeam} turns the flag off under a sibling asserting that its own
 * {@code $/setTrace} handshake left it on. Isolation is symmetric, so one annotation closes both
 * directions.
 */
@Isolated("rebinds LspTrace's process-global sink and enabled flag")
class LspTraceTest {

    private ByteArrayOutputStream captured;

    @AfterEach
    void resetSeam() {
        LspTrace.setEnabled(false);
        LspTrace.sinkForTesting(null);
        LspTrace.slowMsForTesting(null);
    }

    @Test
    @DisplayName("off by default: no output, and the span is the shared no-op instance")
    void offByDefault() {
        assertThat(LspTrace.enabled())
            .as("no test sets the enabling property, so the seam must start off")
            .isFalse();

        redirectSink();
        var first = LspTrace.span("phase");
        var second = LspTrace.span("phase");
        // Identity, not just equality: the disabled path must allocate nothing per span,
        // which is what lets the seam sit inside a per-keystroke edit.
        assertThat(first).isSameAs(second);

        first.detail("key", "value").detail("other", 1);
        first.close();
        second.close();

        assertThat(emitted()).isEmpty();
    }

    @Test
    @DisplayName("the default sink is stderr, never the JSON-RPC stream on stdout")
    void defaultSinkIsNotStdout() {
        // The stdio launcher speaks JSON-RPC over stdout; a single stray byte there would
        // desynchronise the framing and hang the client. Every span line goes through
        // sink(), so this one identity check covers all of them.
        assertThat(LspTrace.sink()).isNotSameAs(System.out);
    }

    @Test
    @DisplayName("an open span emits its line before it closes, so a hung phase is visible")
    void emitsOpenLineBeforeClose() {
        redirectSink();
        LspTrace.setEnabled(true);

        var span = LspTrace.span("stuck");
        // Read the sink before closing: this is the shape of a phase that never returns,
        // and making that case readable is the reason open and close are separate lines.
        assertThat(emitted()).anySatisfy(line ->
            assertThat(line).contains("lsp-trace >").contains("stuck"));

        span.close();
        assertThat(emitted()).anySatisfy(line ->
            assertThat(line).contains("lsp-trace <").contains("stuck"));
    }

    @Test
    @DisplayName("close line carries elapsed time, thread name, and accumulated details")
    void closeLineCarriesContext() {
        redirectSink();
        LspTrace.setEnabled(true);

        try (var span = LspTrace.span("diagnostics.compute")) {
            span.detail("uri", "file:///schema.graphqls").detail("directives", 42);
        }

        var closeLine = lineContaining("lsp-trace <", "diagnostics.compute");
        assertThat(closeLine)
            .contains("ms")
            .contains("thread=" + Thread.currentThread().getName())
            .contains("uri=file:///schema.graphqls")
            .contains("directives=42");
    }

    @Test
    @DisplayName("nested spans indent by depth and unwind cleanly")
    void nestedSpansIndent() {
        redirectSink();
        LspTrace.setEnabled(true);

        try (var outer = LspTrace.span("outer")) {
            outer.detail("level", 0);
            try (var inner = LspTrace.span("inner")) {
                inner.detail("level", 1);
            }
        }
        // A sibling opened after the nest unwinds must be back at depth 0; if close() had
        // leaked the per-thread depth counter, this line would carry the inner indentation.
        try (var sibling = LspTrace.span("sibling")) {
            sibling.detail("level", 0);
        }

        int outerIndent = indentBefore(lineContaining("lsp-trace >", "outer"), "outer");
        int innerIndent = indentBefore(lineContaining("lsp-trace >", "inner"), "inner");
        int siblingIndent = indentBefore(lineContaining("lsp-trace >", "sibling"), "sibling");
        assertThat(innerIndent).isGreaterThan(outerIndent);
        assertThat(siblingIndent)
            .as("the post-nest sibling is back at the outer span's depth")
            .isEqualTo(outerIndent);
    }

    @Test
    @DisplayName("a double close is ignored, and a span closed on another thread is not counted as it")
    void doubleCloseIsIgnored() throws InterruptedException {
        redirectSink();
        LspTrace.setEnabled(true);

        var span = LspTrace.span("double-close");
        span.close();
        span.close();

        // The sink is process-global, so while this case holds the seam on, anything else running
        // in the JVM emits into the same capture. One joined thread stands in for that: no sleep,
        // no timing, and the sink is quiet again before the assertions read it.
        var foreign = new Thread(() -> LspTrace.span("foreign-emitter").close(), "foreign-emitter");
        foreign.start();
        foreign.join();

        // Scoped to the span this case opened, and over the list rather than its size, so a future
        // breach prints the lines it found instead of "expected: 1 but was: 2".
        assertThat(linesContaining("lsp-trace <", "double-close"))
            .as("the second close emits nothing, and no other span's close is this span's")
            .hasSize(1);
        assertThat(linesContaining("lsp-trace <", "foreign-emitter"))
            .as("the foreign close really did land in the capture, so the scoping above is doing work")
            .hasSize(1);
    }

    @Test
    @DisplayName("details attached before an exception still reach the close line")
    void detailsSurviveAnException() {
        redirectSink();
        LspTrace.setEnabled(true);

        try (var span = LspTrace.span("throwing")) {
            span.detail("uri", "file:///schema.graphqls");
            throw new IllegalStateException("boom");
        } catch (IllegalStateException expected) {
            // try-with-resources closes the span on the way out, so the context gathered
            // before the throw is exactly what a crash-time trace needs to carry.
        }

        assertThat(lineContaining("lsp-trace <", "throwing")).contains("uri=file:///schema.graphqls");
    }

    @Test
    @DisplayName("every line carries a time of day, so an unmatched open span can be placed")
    void everyLineIsStamped() {
        redirectSink();
        LspTrace.setEnabled(true);

        try (var span = LspTrace.span("stamped")) {
            span.detail("k", "v");
        }

        // An unmatched '>' is the seam's headline signal, and without a clock it says where
        // the server stuck but not when, so it cannot be lined up against the user's report
        // of when the editor froze. Both lines are stamped, not just the close.
        assertThat(emitted())
            .filteredOn(line -> line.contains("lsp-trace"))
            .isNotEmpty()
            .allSatisfy(line -> assertThat(line).matches("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} lsp-trace .*"));
    }

    @Test
    @DisplayName("a header line records the date and the resolved threshold, once per sink")
    void headerIsWrittenOncePerSink() {
        redirectSink();
        LspTrace.slowMsForTesting(250L);
        LspTrace.setEnabled(true);

        try (var _ = LspTrace.span("first")) {
            // Two spans, one header: the artifact is self-describing without repeating itself.
        }
        try (var _ = LspTrace.span("second")) {
            // no-op
        }

        var headers = emitted().stream().filter(l -> l.contains("lsp-trace header")).toList();
        assertThat(headers).hasSize(1);
        assertThat(headers.getFirst())
            .as("per-line stamps are time-of-day only, so the date has to live here")
            .contains("date=" + LocalDate.now())
            .contains("slowMs=250");
    }

    @Test
    @DisplayName("the SLOW tag fires above the threshold and stays off below it")
    void slowTagTracksTheThreshold() {
        redirectSink();
        LspTrace.slowMsForTesting(0L);
        LspTrace.setEnabled(true);
        try (var _ = LspTrace.span("everything-is-slow")) {
            // Threshold zero: any duration at all is at or above it.
        }
        assertThat(lineContaining("lsp-trace <", "everything-is-slow")).contains(" SLOW");

        redirectSink();
        LspTrace.slowMsForTesting(600_000L);
        try (var _ = LspTrace.span("nothing-is-slow")) {
            // Ten minutes: no test span reaches it.
        }
        assertThat(lineContaining("lsp-trace <", "nothing-is-slow")).doesNotContain("SLOW");
    }

    @Test
    @DisplayName("the file sink writes span lines to the named path, creating parent directories")
    void fileSinkWritesToTheNamedPath(@TempDir Path dir) throws Exception {
        var target = dir.resolve("nested/deeper/lsp-trace.log");
        var fileSink = LspTrace.openSink(target.toString());
        LspTrace.sinkForTesting(fileSink);
        LspTrace.setEnabled(true);

        try (var span = LspTrace.span("to-a-file")) {
            span.detail("uri", "file:///schema.graphqls");
        }
        // Released before reading rather than left to @AfterEach's sink swap, which would
        // orphan the handle and leave @TempDir unable to clean up on some platforms.
        fileSink.close();

        assertThat(target).exists();
        assertThat(Files.readString(target, StandardCharsets.UTF_8))
            .contains("lsp-trace header")
            .contains("to-a-file")
            .contains("uri=file:///schema.graphqls");
    }

    @Test
    @DisplayName("an unopenable trace file falls back to stderr rather than failing the server")
    void fileSinkFallsBackToStderr(@TempDir Path dir) throws Exception {
        // A directory where the file should be: opening it as a file cannot succeed. Tracing
        // is a diagnostic, so a bad path must degrade rather than take the LSP down with it.
        var occupied = dir.resolve("occupied");
        Files.createDirectory(occupied);

        assertThat(LspTrace.openSink(occupied.toString())).isSameAs(System.err);
    }

    @Test
    @DisplayName("the environment variable enables the seam independently of the property")
    void envVarArmEnables() {
        // The pure-function seam exists because a test cannot set an environment variable for
        // the JVM it runs in; without it this arm could only be checked by reading the source.
        assertThat(LspTrace.enabledFrom(null, "true")).isTrue();
        assertThat(LspTrace.enabledFrom("true", null)).isTrue();
        assertThat(LspTrace.enabledFrom("true", "false")).isTrue();
        assertThat(LspTrace.enabledFrom(null, null)).isFalse();
        assertThat(LspTrace.enabledFrom("false", "false")).isFalse();
        assertThat(LspTrace.enabledFrom("yes", "1"))
            .as("Boolean.parseBoolean accepts only \"true\", so anything else reads as off")
            .isFalse();
    }

    @Test
    @DisplayName("primitive detail overloads render identically to the boxing one")
    void primitiveDetailOverloadsRender() {
        redirectSink();
        LspTrace.setEnabled(true);

        try (var span = LspTrace.span("counts")) {
            span.detail("ints", 42).detail("longs", 9_000_000_000L).detail("objects", "text");
        }

        // The overloads exist so a count on a per-keystroke path does not box at the call
        // site while the seam is off; the rendered line must not change because of them.
        assertThat(lineContaining("lsp-trace <", "counts"))
            .contains("ints=42")
            .contains("longs=9000000000")
            .contains("objects=text");
    }

    private void redirectSink() {
        captured = new ByteArrayOutputStream();
        LspTrace.sinkForTesting(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    private List<String> emitted() {
        return captured.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private List<String> linesContaining(String marker, String name) {
        return emitted().stream().filter(l -> l.contains(marker) && l.contains(name)).toList();
    }

    private String lineContaining(String marker, String name) {
        var matches = linesContaining(marker, name);
        if (matches.isEmpty()) {
            throw new AssertionError("no line with " + marker + " and " + name + " in " + emitted());
        }
        return matches.getFirst();
    }

    private static int indentBefore(String line, String name) {
        int nameIdx = line.indexOf(name);
        int spaces = 0;
        for (int i = nameIdx - 1; i >= 0 && line.charAt(i) == ' '; i--) {
            spaces++;
        }
        return spaces;
    }
}
