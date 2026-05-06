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
 * Class-name completion against the actual {@code ExternalCodeReference}
 * shape in {@code directives.graphqls}: each of {@code @service},
 * {@code @condition}, and {@code @record} carries one outer arg whose
 * key matches the directive name and whose value is an object with
 * {@code className} / {@code method} / {@code argMapping} fields.
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
    void serviceClassNameCompletesFqns() {
        String source = "type Query { x: Int @service(service: {className: \"\", method: \"foo\"}) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "service");

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactly("com.example.FilmService", "com.example.CategoryConditions");
    }

    @Test
    void conditionClassNameCompletesFqns() {
        String source = "type Query { x: Int @condition(condition: {className: \"\", method: \"foo\"}) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "condition");

        assertThat(items).hasSize(2);
    }

    @Test
    void recordClassNameCompletesFqns() {
        String source = "input Foo @record(record: {className: \"\"}) { bar: Int }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "record");

        assertThat(items).hasSize(2);
    }

    @Test
    void cursorOutsideClassNameReturnsEmpty() {
        // Cursor inside method:, not className:.
        String source = "type Query { x: Int @service(service: {className: \"com.example.FilmService\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor, "service");

        assertThat(items).isEmpty();
    }

    @Test
    void cursorInArgMappingReturnsEmpty() {
        String source = "type Query { x: Int @service(service: {className: \"com.example.FilmService\", method: \"foo\", argMapping: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor, "service");

        assertThat(items).isEmpty();
    }

    @Test
    void externalFieldClassNameCompletesFqns() {
        String source = "type Query { x: Int @externalField(reference: {className: \"\"}) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "externalField");

        assertThat(items).hasSize(2);
    }

    @Test
    void enumClassNameCompletesFqns() {
        String source = "enum Foo @enum(enumReference: {className: \"\"}) { A B }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "enum");

        assertThat(items).hasSize(2);
    }

    @Test
    void tableMethodClassNameCompletesFqns() {
        String source = "type Query { x: Int @tableMethod(tableMethodReference: {className: \"\", method: \"foo\"}) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "tableMethod");

        assertThat(items).hasSize(2);
    }

    @Test
    void batchKeyLifterClassNameCompletesFqns() {
        String source = "type Query { x: Int @batchKeyLifter(lifter: {className: \"\", method: \"foo\"}, targetColumns: [\"id\"]) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "batchKeyLifter");

        assertThat(items).hasSize(2);
    }

    @Test
    void referencePathConditionClassNameCompletesFqns() {
        String source = "type Query { x: Int @reference(path: [{condition: {className: \"\"}}]) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "reference");

        assertThat(items).hasSize(2);
    }

    @Test
    void referencePathTopLevelClassNameDoesNotComplete() {
        // No `condition` ancestor object_field: the className lives directly
        // under a path[] element, which is a ReferenceElement, not an
        // ExternalCodeReference. Must not light up the className completion.
        String source = "type Query { x: Int @reference(path: [{className: \"\"}]) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "reference");

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
