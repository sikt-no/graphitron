package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.MethodCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Method-name completion against the {@code ExternalCodeReference}
 * shape: cursor inside {@code @service(service: {method: "..."})} or
 * {@code @condition(condition: {method: "..."})} reads the sibling
 * {@code className} field on the same nested object to scope candidates.
 */
class MethodCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofClasspath(tmp, List.of(
            StoreFixture.jarClass("com.example.FilmService", List.of(
                StoreFixture.method("list", "List", StoreFixture.parameter("limit", "int")),
                StoreFixture.method("get", "Film", StoreFixture.parameter("id", "int"))))));
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void serviceMethodCompletesMethodsOfSiblingClassName() {
        String source = "type Query { x: Int @service(service: {className: \"com.example.FilmService\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor, "service");

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactlyInAnyOrder("list", "get");
        var list = items.stream().filter(i -> i.getLabel().equals("list")).findFirst().orElseThrow();
        assertThat(list.getDetail()).isEqualTo("List list(int limit)");
    }

    @Test
    void conditionMethodCompletesMethodsOfSiblingClassName() {
        String source = "type Query { x: Int @condition(condition: {className: \"com.example.FilmService\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor, "condition");

        assertThat(items).hasSize(2);
    }

    @Test
    void unknownClassNameReturnsNoCompletions() {
        String source = "type Query { x: Int @service(service: {className: \"com.example.Missing\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor, "service");

        assertThat(items).isEmpty();
    }

    @Test
    void missingClassNameReturnsNoCompletions() {
        String source = "type Query { x: Int @service(service: {method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor, "service");

        assertThat(items).isEmpty();
    }

    @Test
    void sourceRowMethodCompletesMethodsOfSiblingClassName() {
        // The @sourceRow gap closes here on the method-binding side. @sourceRow's
        // className: and method: are flat directive args, not nested in an
        // ExternalCodeReference object. The canonical overlay's
        // MethodNameBinding(@sourceRow(className:)) reads the sibling
        // value off the directive's argument list directly.
        String source = "type Foo { x: Int @sourceRow(className: \"com.example.FilmService\", method: \"\") }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor, "sourceRow");

        assertThat(items).extracting(i -> i.getLabel()).containsExactlyInAnyOrder("list", "get");
    }

    @Test
    void cursorOutsideMethodReturnsEmpty() {
        // Cursor inside className:, not method:.
        String source = "type Query { x: Int @service(service: {className: \"com.example.FilmService\", method: \"foo\"}) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

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
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = no.sikt.graphitron.lsp.completions.CompletionContext.from(locOpt.get(), bytes);
        return MethodCompletions.generate(VOCAB, store.handle(), context, directive, cursor, bytes);
    }
}
