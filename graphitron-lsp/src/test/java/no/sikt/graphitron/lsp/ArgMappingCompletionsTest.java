package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.ArgMappingCompletions;
import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * argMapping completion: the left side offers the named method's parameter names out of
 * {@code jvm_method_parameter}, the right side offers the enclosing field's GraphQL argument names
 * read off the buffer, and dot-paths defer. All single-line / ASCII, so the LSP character column
 * equals the tree-sitter byte column.
 */
class ArgMappingCompletionsTest {

    private static final LspVocabulary VOCAB = BundledVocabulary.get();

    private static final String CLASS = "com.example.PriceService";

    @Test
    void leftSideOffersMethodParameterNames(@TempDir Path tmp) {
        String source = field("argMapping: \"\"");
        int col = source.indexOf("argMapping: \"") + "argMapping: \"".length();

        try (var fixture = classpath(tmp, StoreFixture.method("compute", "Object",
            StoreFixture.parameter("film", "Object"), StoreFixture.parameter("limit", "Object")))) {
            // Declaration order, which is the order an author reads the parameter list in.
            assertThat(labels(fixture.handle(), source, col)).containsExactly("film", "limit");
        }
    }

    /**
     * Every overload's parameter names, deduplicated. The schema names a method by name alone, so no
     * census can say which overload an author meant; the projection resolved to whichever came first,
     * which quietly hid the other one's names.
     */
    @Test
    void leftSideOffersEveryOverloadsParameterNames(@TempDir Path tmp) {
        String source = field("argMapping: \"\"");
        int col = source.indexOf("argMapping: \"") + "argMapping: \"".length();

        try (var fixture = classpath(tmp,
            StoreFixture.method("compute", "Object", StoreFixture.parameter("film", "Object")),
            StoreFixture.method("compute", "Object",
                StoreFixture.parameter("film", "Object"), StoreFixture.parameter("limit", "Integer")))) {
            assertThat(labels(fixture.handle(), source, col)).containsExactly("film", "limit");
        }
    }

    @Test
    void leftSideSuppressedWhenParameterNamesAbsent(@TempDir Path tmp) {
        String source = field("argMapping: \"\"");
        int col = source.indexOf("argMapping: \"") + "argMapping: \"".length();

        try (var fixture = classpath(tmp, StoreFixture.method("compute", "Object",
            StoreFixture.parameter(null, "Object")))) {
            assertThat(labels(fixture.handle(), source, col)).isEmpty();
        }
    }

    @Test
    void leftSideSuppressedWhenTheClassWasNeverWalked(@TempDir Path tmp) {
        String source = field("argMapping: \"\"");
        int col = source.indexOf("argMapping: \"") + "argMapping: \"".length();

        try (var fixture = StoreFixture.ofClasspath(tmp, List.of())) {
            assertThat(labels(fixture.handle(), source, col)).isEmpty();
        }
    }

    @Test
    void rightSideOffersEnclosingFieldArguments(@TempDir Path tmp) {
        String source = field("argMapping: \"film: \"");
        int col = source.indexOf("film: ") + "film: ".length();

        try (var fixture = classpath(tmp, StoreFixture.method("compute", "Object",
            StoreFixture.parameter("film", "Object")))) {
            assertThat(labels(fixture.handle(), source, col))
                .containsExactlyInAnyOrder("first", "after");
        }
    }

    @Test
    void rightSideDefersOnDotPath(@TempDir Path tmp) {
        String source = field("argMapping: \"film: after.\"");
        int col = source.indexOf("after.") + "after.".length();

        try (var fixture = classpath(tmp, StoreFixture.method("compute", "Object",
            StoreFixture.parameter("film", "Object")))) {
            assertThat(labels(fixture.handle(), source, col)).isEmpty();
        }
    }

    /** A census holding one class with the given methods, the shape a classpath scan writes. */
    private static StoreFixture classpath(Path directory, CompletionData.Method... methods) {
        return StoreFixture.ofClasspath(directory,
            List.of(StoreFixture.jarClass(CLASS, List.of(methods))));
    }

    /**
     * Field carrying GraphQL args {@code first} / {@code after} and a @service whose argMapping
     * content is supplied by the caller.
     */
    private static String field(String argMapping) {
        return "type Query { f(first: Int, after: String): Int "
            + "@service(service: {className: \"" + CLASS + "\", method: \"compute\", "
            + argMapping + "}) }\n";
    }

    private static List<String> labels(StoreHandle handle, String source, int col) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var cursor = new Point(0, col);
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = CompletionContext.from(locOpt.get(), bytes);
        return ArgMappingCompletions.generate(
                VOCAB, handle, context, directive, cursor, new Position(0, col), bytes)
            .stream().map(CompletionItem::getLabel).toList();
    }
}
