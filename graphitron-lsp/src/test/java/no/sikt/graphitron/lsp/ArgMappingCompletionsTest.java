package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.ArgMappingCompletions;
import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * argMapping completion: left side offers the resolved method's parameter
 * names, right side offers the enclosing field's GraphQL argument names, and
 * dot-paths defer. All single-line / ASCII, so the LSP character column equals
 * the tree-sitter byte column.
 */
class ArgMappingCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    private static CompletionData catalog(String... paramNames) {
        var params = new java.util.ArrayList<CompletionData.Parameter>();
        for (String n : paramNames) params.add(new CompletionData.Parameter(n, "Object", null, ""));
        var method = new CompletionData.Method("compute", "Object", "", List.copyOf(params));
        return new CompletionData(List.of(), List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.PriceService", "com.example.PriceService", "",
                List.of(method), List.of())));
    }

    private static CompletionData catalogNullParam() {
        var method = new CompletionData.Method("compute", "Object", "",
            List.of(new CompletionData.Parameter(null, "Object", null, "")));
        return new CompletionData(List.of(), List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.PriceService", "com.example.PriceService", "",
                List.of(method), List.of())));
    }

    @Test
    void leftSideOffersMethodParameterNames() {
        String source = field("argMapping: \"\"");
        int col = source.indexOf("argMapping: \"") + "argMapping: \"".length();
        var items = run(catalog("film", "limit"), source, col);
        assertThat(items).extracting(CompletionItem::getLabel)
            .containsExactlyInAnyOrder("film", "limit");
    }

    @Test
    void leftSideSuppressedWhenParameterNamesNull() {
        String source = field("argMapping: \"\"");
        int col = source.indexOf("argMapping: \"") + "argMapping: \"".length();
        assertThat(run(catalogNullParam(), source, col)).isEmpty();
    }

    @Test
    void rightSideOffersEnclosingFieldArguments() {
        String source = field("argMapping: \"film: \"");
        int col = source.indexOf("film: ") + "film: ".length();
        var items = run(catalog("film"), source, col);
        assertThat(items).extracting(CompletionItem::getLabel)
            .containsExactlyInAnyOrder("first", "after");
    }

    @Test
    void rightSideDefersOnDotPath() {
        String source = field("argMapping: \"film: after.\"");
        int col = source.indexOf("after.") + "after.".length();
        assertThat(run(catalog("film"), source, col)).isEmpty();
    }

    /**
     * Field carrying GraphQL args {@code first} / {@code after} and a @service
     * whose argMapping content is supplied by the caller.
     */
    private static String field(String argMapping) {
        return "type Query { f(first: Int, after: String): Int "
            + "@service(service: {className: \"com.example.PriceService\", method: \"compute\", "
            + argMapping + "}) }\n";
    }

    private static List<CompletionItem> run(CompletionData data, String source, int col) {
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
            VOCAB, data, context, directive, cursor, new Position(0, col), bytes);
    }
}
