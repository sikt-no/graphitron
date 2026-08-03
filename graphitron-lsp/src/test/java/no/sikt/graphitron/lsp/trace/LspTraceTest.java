package no.sikt.graphitron.lsp.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of the trace seam itself. Two of these assertions are what make the seam safe
 * to leave sitting on per-keystroke paths and in the stdio deployment: off allocates
 * nothing and emits nothing, and the default sink is never {@code System.out}.
 */
class LspTraceTest {

    private ByteArrayOutputStream captured;

    @AfterEach
    void resetSeam() {
        LspTrace.setEnabled(false);
        LspTrace.sinkForTesting(null);
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
    @DisplayName("a double close is ignored rather than corrupting the depth counter")
    void doubleCloseIsIgnored() {
        redirectSink();
        LspTrace.setEnabled(true);

        var span = LspTrace.span("phase");
        span.close();
        span.close();

        assertThat(emitted().stream().filter(l -> l.contains("lsp-trace <")).count()).isEqualTo(1);
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

    private void redirectSink() {
        captured = new ByteArrayOutputStream();
        LspTrace.sinkForTesting(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    private List<String> emitted() {
        return captured.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private String lineContaining(String marker, String name) {
        var lines = emitted();
        return lines.stream()
            .filter(l -> l.contains(marker) && l.contains(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no line with " + marker + " and " + name + " in " + lines));
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
