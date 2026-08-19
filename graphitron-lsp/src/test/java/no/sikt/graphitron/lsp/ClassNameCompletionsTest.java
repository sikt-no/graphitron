package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.ClassNameCompletions;
import no.sikt.graphitron.lsp.completions.CompletionContext;
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
 * Class-name completion against the actual {@code ExternalCodeReference}
 * shape in {@code directives.graphqls}: {@code @service} / {@code @condition} /
 * {@code @enum} (and the flat {@code @sourceRow}) each
 * carry a className slot whose value is an FQN, completed from the catalog.
 * {@code @record} shares the same {@code ExternalCodeReference.className}
 * coordinate but is deprecated and ignored, so its className slot is
 * carved out and offers no completion.
 *
 * <p>The candidates are {@code jvm_class} rows captured from a classpath census, one graph's own, so
 * these cases also stand on the scoping: a class this graph's walk never met is not a candidate
 * however much store it shares.
 */
class ClassNameCompletionsTest {

    private static final LspVocabulary VOCAB = BundledVocabulary.get();

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofClasspath(tmp, List.of(
            StoreFixture.jarClass("com.example.FilmService", List.of()),
            StoreFixture.jarClass("com.example.CategoryConditions", List.of())));
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void serviceClassNameCompletesFqns() {
        String source = "type Query { x: Int @service(service: {className: \"\", method: \"foo\"}) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "service");

        // Alphabetical within a residence rank, not the order the walk happened to meet them in:
        // the projection carried the scan's own sequence, which nothing could explain to an author.
        assertThat(items).extracting(i -> i.getLabel())
            .containsExactly("com.example.CategoryConditions", "com.example.FilmService");
    }

    @Test
    void conditionClassNameCompletesFqns() {
        String source = "type Query { x: Int @condition(condition: {className: \"\", method: \"foo\"}) }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "condition");

        assertThat(items).hasSize(2);
    }

    @Test
    void recordClassName_carveOut_offersNoCompletion() {
        // @record is deprecated and ignored, so its className slot binds no class and offers
        // no FQN completion, even though its ExternalCodeReference.className coordinate is identical
        // to @enum's (which does complete, see enumClassNameCompletesFqns). The carve-out gates on
        // the enclosing directive name.
        String source = "input Foo @record(record: {className: \"\"}) { bar: Int }\n";
        Point cursor = new Point(0, source.indexOf('"') + 1);

        var items = run(source, cursor, "record");

        assertThat(items).isEmpty();
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
    void sourceRowClassNameCompletesFqns() {
        // The @sourceRow gap closes here. Today's hand-coded DirectiveDefinitions
        // doesn't list @sourceRow; the canonical overlay declares both
        // @sourceRow(className:) and @sourceRow(method:) as ECR-shaped
        // bindings, so completions now fire on either.
        String source = "type Foo { x: Int @sourceRow(className: \"\", method: \"foo\") }\n";
        Point cursor = new Point(0, source.indexOf("className: \"") + "className: \"".length());

        var items = run(source, cursor, "sourceRow");

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactlyInAnyOrder("com.example.FilmService", "com.example.CategoryConditions");
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
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = CompletionContext.from(locOpt.get(), bytes);
        return ClassNameCompletions.generate(VOCAB, store.handle(), context);
    }
}
