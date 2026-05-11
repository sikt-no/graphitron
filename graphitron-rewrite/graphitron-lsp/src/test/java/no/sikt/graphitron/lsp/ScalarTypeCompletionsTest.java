package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.ScalarTypeCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Completion for {@code @scalarType(scalar:)} on a {@code scalar X} declaration.
 * Convention-table FQNs are the candidate set; the entry matching the
 * enclosing scalar's SDL name (when present) is offered first.
 */
class ScalarTypeCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();
    private static final CompletionData DATA = CompletionData.empty();

    @Test
    void scalarMatchingConventionEntryPrefersExtendedScalarsFqn() {
        String source = "scalar BigDecimal @scalarType(scalar: \"\")\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        assertThat(items).isNotEmpty();
        assertThat(items.get(0).getLabel())
            .isEqualTo("graphql.scalars.ExtendedScalars.GraphQLBigDecimal");
        assertThat(items).extracting(CompletionItem::getLabel)
            .contains("graphql.scalars.ExtendedScalars.DateTime");
    }

    @Test
    void scalarMatchingPrefixedFormPrefersSameConstant() {
        String source = "scalar GraphQLBigDecimal @scalarType(scalar: \"\")\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        assertThat(items.get(0).getLabel())
            .isEqualTo("graphql.scalars.ExtendedScalars.GraphQLBigDecimal");
    }

    @Test
    void unknownScalarNameStillSuggestsConventionTable() {
        String source = "scalar Money @scalarType(scalar: \"\")\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        assertThat(items).extracting(CompletionItem::getLabel)
            .contains("graphql.scalars.ExtendedScalars.GraphQLBigDecimal",
                "graphql.scalars.ExtendedScalars.DateTime",
                "graphql.scalars.ExtendedScalars.UUID");
        // No convention entry matches Money, so the first item is just the
        // first deduplicated FQN; assert no Money-specific FQN was invented.
        assertThat(items).extracting(CompletionItem::getLabel)
            .noneMatch(l -> l.contains("Money"));
    }

    @Test
    void cursorOnDifferentDirectiveReturnsEmpty() {
        String source = "type Foo @table(name: \"\") { x: Int }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        assertThat(items).isEmpty();
    }

    private static List<CompletionItem> run(String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        return ScalarTypeCompletions.generate(VOCAB, DATA, directive, cursor, bytes);
    }
}
