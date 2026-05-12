package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.ClassNameCompletions;
import no.sikt.graphitron.lsp.completions.MethodCompletions;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the LSP against the real {@code @service} / {@code @condition} /
 * {@code @record} directive shape declared in
 * {@code directives.graphqls}: a single nested {@code ExternalCodeReference}
 * input under an outer arg whose key matches the directive name, with
 * {@code className} / {@code method} / {@code argMapping} fields on the
 * nested object.
 *
 * <p>Phase 5a/5b/5c shipped against a flat-arg shape
 * ({@code @service(class: "X", method: "foo")}) that does not exist;
 * the providers silently returned empty against real schemas. Phase 5d
 * corrects the shape and adds this smoke test against schema fragments
 * lifted directly from the sakila example so the bug cannot recur.
 */
class DirectiveShapeSmokeTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void serviceDirectiveSakilaShapeProducesCompletionsAndDiagnostics() {
        // Lifted from graphitron-sakila-example/.../schema.graphqls.
        String source = """
            type Query {
                filmsByService(ids: [Int!]!): [Film!]!
                    @service(service: {
                        className: "no.sikt.graphitron.rewrite.test.services.SampleQueryService",
                        method: "filmsByService"
                    })
            }
            """;
        var data = catalogWith(
            "no.sikt.graphitron.rewrite.test.services.SampleQueryService",
            "filmsByService"
        );
        // Class-name completion: cursor inside className: value.
        Point classCursor = pointInside(source, "no.sikt.graphitron");
        var classItems = ClassNameCompletions.generate(
            VOCAB, data, directiveAt(source, classCursor), classCursor,
            source.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(classItems).extracting(i -> i.getLabel())
            .contains("no.sikt.graphitron.rewrite.test.services.SampleQueryService");

        // Method completion: cursor inside method: value.
        Point methodCursor = pointInside(source, "filmsByService\"\n");
        // Land inside the method:'s value (after the opening quote of "filmsByService").
        methodCursor = adjustToInsideValue(source, methodCursor, "method: \"filmsByService\"");
        var methodItems = MethodCompletions.generate(
            VOCAB, data, directiveAt(source, methodCursor), methodCursor,
            source.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(methodItems).extracting(i -> i.getLabel()).contains("filmsByService");

        // Diagnostics: this schema is internally consistent; no errors.
        var diags = Diagnostics.compute("", new WorkspaceFile(1, source), data, LspSchemaSnapshot.unavailable(), ValidationReport.empty());
        assertThat(diags).isEmpty();
    }

    @Test
    void serviceDirectiveSakilaShapeFlagsUnknownClass() {
        String source = """
            type Query {
                filmsByService(ids: [Int!]!): [Film!]!
                    @service(service: {
                        className: "com.example.NotInClasspath",
                        method: "filmsByService"
                    })
            }
            """;
        var data = catalogWith(
            "no.sikt.graphitron.rewrite.test.services.SampleQueryService",
            "filmsByService"
        );

        var diags = Diagnostics.compute("", new WorkspaceFile(1, source), data, LspSchemaSnapshot.unavailable(), ValidationReport.empty());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("NotInClasspath");
    }

    @Test
    void conditionDirectiveSakilaShapeFlagsUnknownMethod() {
        // Lifted from sakila Query.filmsOuterOverrideTableInput.
        String source = """
            type Query {
                filmsOuterOverrideTableInput(filter: FilmConditionInput): [Film!]!
                    @condition(condition: {
                        className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                        method: "ghostMethod"
                    }, override: true)
            }
            """;
        var data = catalogWith(
            "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
            "outerOverrideMethod"
        );

        var diags = Diagnostics.compute("", new WorkspaceFile(1, source), data, LspSchemaSnapshot.unavailable(), ValidationReport.empty());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("ghostMethod");
    }

    @Test
    void recordDirectiveSakilaShapeProducesClassHover() {
        String source = """
            input FooInput @record(record: {className: "com.example.FooDto"}) {
                bar: Int
            }
            """;
        var data = catalogWith("com.example.FooDto", null);
        Point cursor = pointInside(source, "com.example");

        var hover = Hovers.compute(new WorkspaceFile(1, source), data,
            LspSchemaSnapshot.unavailable(), cursor).orElseThrow();

        assertThat(hover.getContents().getRight().getValue())
            .contains("com.example.FooDto");
    }

    @Test
    void argMappingPositionDoesNotProduceClassNameCompletions() {
        // The cursor is inside argMapping:'s value; we must not offer class
        // FQNs there. argMapping completion is its own follow-up slice.
        String source = """
            type Query {
                filmsByServiceRenamed(ids: [Int!]!): [Film!]!
                    @service(service: {
                        className: "com.example.FilmService",
                        method: "filmsByServiceRenamed",
                        argMapping: ""
                    })
            }
            """;
        var data = catalogWith("com.example.FilmService", "filmsByServiceRenamed");
        // Land between the empty quotes of argMapping.
        int idx = source.indexOf("argMapping: \"") + "argMapping: \"".length();
        Point cursor = lspPoint(source, idx);

        var classItems = ClassNameCompletions.generate(
            VOCAB, data, directiveAt(source, cursor), cursor,
            source.getBytes(StandardCharsets.UTF_8)
        );
        var methodItems = MethodCompletions.generate(
            VOCAB, data, directiveAt(source, cursor), cursor,
            source.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(classItems).isEmpty();
        assertThat(methodItems).isEmpty();
    }

    private static CompletionData catalogWith(String className, String methodName) {
        var methods = methodName == null
            ? List.<CompletionData.Method>of()
            : List.of(new CompletionData.Method(methodName, "List", "", List.of()));
        return new CompletionData(
            List.of(),
            List.of(),
            List.of(new CompletionData.ExternalReference(className, className, "", methods))
        );
    }

    /**
     * Locates a point inside the first occurrence of {@code needle} in
     * {@code source}, expressed as a tree-sitter {@link Point}.
     */
    private static Point pointInside(String source, String needle) {
        int idx = source.indexOf(needle);
        if (idx < 0) throw new AssertionError("needle '" + needle + "' not in source");
        // Land a couple chars in so we are unambiguously inside.
        return lspPoint(source, idx + Math.max(1, needle.length() / 4));
    }

    private static Point lspPoint(String source, int idx) {
        int row = 0;
        int rowStart = 0;
        for (int i = 0; i < idx; i++) {
            if (source.charAt(i) == '\n') {
                row++;
                rowStart = i + 1;
            }
        }
        // tree-sitter columns are byte offsets within the line; for ASCII
        // schema fragments these match character offsets.
        return Positions.resolve(source.getBytes(StandardCharsets.UTF_8), row, idx - rowStart).tsPoint();
    }

    private static Point adjustToInsideValue(String source, Point fallback, String prefixSearch) {
        int idx = source.indexOf(prefixSearch);
        if (idx < 0) return fallback;
        int valueStart = idx + prefixSearch.indexOf('"') + 1;
        return lspPoint(source, valueStart + 1);
    }

    private static Directives.Directive directiveAt(String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var tree = parser.parse(source).orElseThrow();
        return Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
    }
}
