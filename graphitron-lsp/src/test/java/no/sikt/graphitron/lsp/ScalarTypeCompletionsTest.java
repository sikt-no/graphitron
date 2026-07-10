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
 * Candidates are the {@code className.fieldName} of every {@code GraphQLScalarType}
 * constant carried on {@link CompletionData.ExternalReference#scalarConstants()}
 * (the classpath scan); the constant whose field name matches the enclosing
 * scalar's SDL name (case-insensitive) is offered first.
 */
class ScalarTypeCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    // Source of truth is `data`, not a static table (R464): two scanned classes, a library's
    // extended-scalars holder and a consumer's own scalar holder, each carrying scalar constants.
    private static final CompletionData DATA = new CompletionData(
        List.of(),
        List.of(),
        List.of(
            new CompletionData.ExternalReference(
                "graphql.scalars.ExtendedScalars", "graphql.scalars.ExtendedScalars", "",
                List.of(), List.of(),
                List.of(
                    new CompletionData.ScalarConstant("GraphQLBigDecimal"),
                    new CompletionData.ScalarConstant("DateTime"),
                    new CompletionData.ScalarConstant("UUID"))),
            new CompletionData.ExternalReference(
                "com.example.Scalars", "com.example.Scalars", "",
                List.of(), List.of(),
                List.of(new CompletionData.ScalarConstant("MONEY")))));

    @Test
    void offersScannedConstantsAsClassDotFieldItems() {
        String source = "scalar Foo @scalarType(scalar: \"\")\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        // Every scanned constant is offered, including the consumer's own (com.example.Scalars.MONEY),
        // which a hardcoded extended-scalars table could never surface.
        assertThat(items).extracting(CompletionItem::getLabel)
            .contains(
                "graphql.scalars.ExtendedScalars.GraphQLBigDecimal",
                "graphql.scalars.ExtendedScalars.DateTime",
                "graphql.scalars.ExtendedScalars.UUID",
                "com.example.Scalars.MONEY");
    }

    @Test
    void scalarMatchingConstantFieldNameOfferedFirst() {
        String source = "scalar UUID @scalarType(scalar: \"\")\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        assertThat(items).isNotEmpty();
        assertThat(items.get(0).getLabel()).isEqualTo("graphql.scalars.ExtendedScalars.UUID");
    }

    @Test
    void fieldNameMatchIsCaseInsensitive() {
        String source = "scalar uuid @scalarType(scalar: \"\")\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = run(source, cursor);

        // scalar uuid matches the UUID constant case-insensitively, so it leads.
        assertThat(items.get(0).getLabel()).isEqualTo("graphql.scalars.ExtendedScalars.UUID");
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
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = no.sikt.graphitron.lsp.completions.CompletionContext.from(locOpt.get(), bytes);
        return ScalarTypeCompletions.generate(VOCAB, DATA, context, directive, bytes);
    }
}
