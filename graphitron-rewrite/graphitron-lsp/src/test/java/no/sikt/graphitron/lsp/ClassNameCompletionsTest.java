package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.ClassNameCompletions;
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
 * End-to-end tests for the {@code @service} / {@code @condition} /
 * {@code @record} class-name completion provider. Each test parses a
 * fragment with the cursor inside the relevant slot and asserts the
 * scanner-derived FQN list comes back as completion items.
 */
class ClassNameCompletionsTest {

    private static final CompletionData DATA = new CompletionData(
        List.of(),
        List.of(),
        List.of(
            ext("com.example.FilmService"),
            ext("com.example.CategoryConditions")
        )
    );

    @Test
    void serviceClassArgumentCompletesFqns() {
        String source = "type Query { x: Int @service(class: \"\", method: \"foo\") }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "service");

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactly("com.example.FilmService", "com.example.CategoryConditions");
    }

    @Test
    void conditionClassArgumentCompletesFqns() {
        String source = "type Query { x: Int @condition(class: \"\", method: \"foo\") }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "condition");

        assertThat(items).hasSize(2);
    }

    @Test
    void recordClassNameNestedFieldCompletesFqns() {
        String source = "input Foo @record(record: {className: \"\"}) { bar: Int }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "record");

        assertThat(items).hasSize(2);
    }

    @Test
    void cursorOutsideClassArgReturnsEmpty() {
        // Cursor inside method:, not class:.
        String source = "type Query { x: Int @service(class: \"com.example.FilmService\", method: \"\") }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor, "service");

        assertThat(items).isEmpty();
    }

    private static List<org.eclipse.lsp4j.CompletionItem> run(String source, Point cursor, String directiveName) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        return ClassNameCompletions.generate(DATA, directive, cursor, bytes, directiveName);
    }

    private static CompletionData.ExternalReference ext(String fqn) {
        return new CompletionData.ExternalReference(fqn, fqn, "", List.of());
    }
}
