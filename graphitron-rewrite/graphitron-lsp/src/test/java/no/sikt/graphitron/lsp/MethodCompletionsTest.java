package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.MethodCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the {@code @service(method:)} / {@code @condition(method:)}
 * completion provider. The cursor sits inside the {@code method:} string;
 * the provider reads the sibling {@code class:} value to scope candidate
 * methods to that class.
 */
class MethodCompletionsTest {

    private static final CompletionData DATA = new CompletionData(
        List.of(),
        List.of(),
        List.of(new CompletionData.ExternalReference(
            "com.example.FilmService",
            "com.example.FilmService",
            "",
            List.of(
                new CompletionData.Method("list", "List", "",
                    List.of(new CompletionData.Parameter("limit", "int", null, ""))),
                new CompletionData.Method("get", "Film", "",
                    List.of(new CompletionData.Parameter("id", "int", null, "")))
            )
        ))
    );

    @Test
    void methodArgumentCompletesMethodsOfSiblingClass() {
        String source = "type Query { x: Int @service(class: \"com.example.FilmService\", method: \"\") }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactlyInAnyOrder("list", "get");
        var list = items.stream().filter(i -> i.getLabel().equals("list")).findFirst().orElseThrow();
        assertThat(list.getDetail()).isEqualTo("List list(int limit)");
    }

    @Test
    void unknownClassReturnsNoCompletions() {
        String source = "type Query { x: Int @service(class: \"com.example.Missing\", method: \"\") }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void missingClassArgReturnsNoCompletions() {
        String source = "type Query { x: Int @service(method: \"\") }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void cursorOutsideMethodArgReturnsEmpty() {
        // Cursor inside class:, not method:.
        String source = "type Query { x: Int @service(class: \"com.example.FilmService\", method: \"foo\") }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor);

        assertThat(items).isEmpty();
    }

    private static List<org.eclipse.lsp4j.CompletionItem> run(String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        return MethodCompletions.generate(DATA, directive, cursor, bytes);
    }
}
