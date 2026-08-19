package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.ClassNameCompletions;
import no.sikt.graphitron.lsp.completions.MethodCompletions;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    @TempDir
    Path tmp;

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
        // One fixture for every arm: completion and diagnostics both read this census, so a class the
        // one offers is a class the other accepts, and neither can be right about a schema the other
        // is wrong about.
        try (var store = storeWith(
            "no.sikt.graphitron.rewrite.test.services.SampleQueryService", "filmsByService")) {
        // Class-name completion: cursor inside className: value.
        Point classCursor = pointInside(source, "no.sikt.graphitron");
        var classBytes = source.getBytes(StandardCharsets.UTF_8);
        var classDirective = directiveAt(source, classCursor);
        var classLoc = VOCAB.locateAt(classDirective, classCursor, classBytes).orElseThrow();
        var classContext = no.sikt.graphitron.lsp.completions.CompletionContext.from(classLoc, classBytes);
        var classItems = ClassNameCompletions.generate(VOCAB, store.handle(), classContext);
        assertThat(classItems).extracting(i -> i.getLabel())
            .contains("no.sikt.graphitron.rewrite.test.services.SampleQueryService");

        // Method completion: cursor inside method: value.
        Point methodCursor = pointInside(source, "filmsByService\"\n");
        // Land inside the method:'s value (after the opening quote of "filmsByService").
        methodCursor = adjustToInsideValue(source, methodCursor, "method: \"filmsByService\"");
        var methodBytes = source.getBytes(StandardCharsets.UTF_8);
        var methodDirective = directiveAt(source, methodCursor);
        var methodLoc = VOCAB.locateAt(methodDirective, methodCursor, methodBytes).orElseThrow();
        var methodContext = no.sikt.graphitron.lsp.completions.CompletionContext.from(methodLoc, methodBytes);
        var methodItems = MethodCompletions.generate(
            VOCAB, store.handle(), methodContext, methodDirective, methodCursor, methodBytes);
        assertThat(methodItems).extracting(i -> i.getLabel()).contains("filmsByService");

        // Diagnostics: this schema is internally consistent; no errors.
        assertThat(diagnose(source, store)).isEmpty();
        }
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
        try (var store = storeWith(
            "no.sikt.graphitron.rewrite.test.services.SampleQueryService", "filmsByService")) {
            var diags = diagnose(source, store);

            assertThat(diags).hasSize(1);
            assertThat(diags.get(0).getMessage()).contains("NotInClasspath");
        }
    }

    @Test
    void conditionDirectiveSakilaShapeFlagsUnknownMethod() {
        // Lifted from sakila Query.filmsOuterOverrideFilterInput.
        String source = """
            type Query {
                filmsOuterOverrideFilterInput(filter: FilmConditionInput): [Film!]!
                    @condition(condition: {
                        className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                        method: "missingMethod"
                    }, override: true)
            }
            """;
        try (var store = storeWith(
            "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
            "outerOverrideMethod")) {
            var diags = diagnose(source, store);

            assertThat(diags).hasSize(1);
            assertThat(diags.get(0).getMessage()).contains("missingMethod");
        }
    }

    @Test
    void recordDirectiveSakilaShape_carveOut_noClassHover() {
        // The @record(record: {className:}) shape still parses and its className coordinate
        // resolves (the hover falls through to the SDL docstring, proving shape recognition), but
        // @record is deprecated and ignored so there is no live-binding "**Class**" hover on the FQN.
        String source = """
            input FooInput @record(record: {className: "com.example.FooDto"}) {
                bar: Int
            }
            """;
        Point cursor = pointInside(source, "com.example");

        // The class is in the census, so the carve-out is what declines rather than a lookup miss;
        // the docstring that answers instead is a captured row like every other fact here.
        try (var store = storeWith("com.example.FooDto", null)) {
            var hover = Hovers.compute(WorkspaceFileTestSupport.snapshot(source),
                Optional.of(store.handle()), cursor).orElseThrow();

            var md = hover.getContents().getRight().getValue();
            assertThat(md).doesNotContain("**Class**");
            assertThat(md).isNotBlank();
        }
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
        // Land between the empty quotes of argMapping.
        int idx = source.indexOf("argMapping: \"") + "argMapping: \"".length();
        Point cursor = lspPoint(source, idx);

        var argMapBytes = source.getBytes(StandardCharsets.UTF_8);
        var argMapDirective = directiveAt(source, cursor);
        var argMapLoc = VOCAB.locateAt(argMapDirective, cursor, argMapBytes);
        try (var store = storeWith("com.example.FilmService", "filmsByServiceRenamed")) {
            var classItems = argMapLoc
                .map(loc -> ClassNameCompletions.generate(
                    VOCAB, store.handle(),
                    no.sikt.graphitron.lsp.completions.CompletionContext.from(loc, argMapBytes)))
                .orElseGet(List::of);
            var methodItems = argMapLoc
                .map(loc -> MethodCompletions.generate(
                    VOCAB, store.handle(),
                    no.sikt.graphitron.lsp.completions.CompletionContext.from(loc, argMapBytes),
                    argMapDirective, cursor, argMapBytes))
                .orElseGet(List::of);

            assertThat(classItems).isEmpty();
            assertThat(methodItems).isEmpty();
        }
    }

    private static List<org.eclipse.lsp4j.Diagnostic> diagnose(String source, StoreFixture store) {
        return Diagnostics.compute(VOCAB, "", WorkspaceFileTestSupport.snapshot(source),
            Optional.of(store.handle()));
    }

    /** One class, with one method where the case needs one, captured into a store of its own. */
    private StoreFixture storeWith(String className, String methodName) {
        var methods = methodName == null
            ? List.<CompletionData.Method>of()
            : List.of(StoreFixture.method(methodName, "List"));
        return StoreFixture.ofClasspath(tmp, List.of(StoreFixture.jarClass(className, methods)));
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
