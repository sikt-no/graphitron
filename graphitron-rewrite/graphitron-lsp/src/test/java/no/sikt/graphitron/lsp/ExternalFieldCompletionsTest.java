package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.ExternalFieldCompletions;
import no.sikt.graphitron.lsp.completions.MethodCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @externalField} method completion narrows the candidate list to the
 * Field-returning single-parameter lifters, where the generic
 * {@link MethodCompletions} would offer every public method. Discrimination is
 * by enclosing directive name; under any other directive the provider is inert.
 */
class ExternalFieldCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    private static final CompletionData DATA = new CompletionData(
        List.of(),
        List.of(),
        List.of(new CompletionData.ExternalReference(
            "com.example.FilmFields",
            "com.example.FilmFields",
            "",
            List.of(
                // External-field lifter: Field<X> name(Table) — single param, Field return.
                new CompletionData.Method("rentalRate", "Field", "",
                    List.of(new CompletionData.Parameter("film", "Film", null, ""))),
                new CompletionData.Method("title", "Field", "",
                    List.of(new CompletionData.Parameter("film", "Film", null, ""))),
                // Not a lifter: wrong return type.
                new CompletionData.Method("helper", "String", "",
                    List.of(new CompletionData.Parameter("film", "Film", null, ""))),
                // Not a lifter: wrong arity.
                new CompletionData.Method("combine", "Field", "",
                    List.of(
                        new CompletionData.Parameter("a", "Film", null, ""),
                        new CompletionData.Parameter("b", "Film", null, "")))
            ),
            List.of()))
    );

    @Test
    void externalFieldNarrowsToFieldReturningSingleArgMethods() {
        String source = "type Foo { x: Int @externalField(reference: {className: \"com.example.FilmFields\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = externalField(source, cursor);

        assertThat(items).extracting(CompletionItem::getLabel)
            .containsExactlyInAnyOrder("rentalRate", "title");
    }

    @Test
    void genericMethodProviderStillOffersAllMethodsUnderService() {
        // Same class under @service: the generic provider is unfiltered, so the
        // narrowing is specific to @externalField.
        String source = "type Foo { x: Int @service(service: {className: \"com.example.FilmFields\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        var items = method(source, cursor);

        assertThat(items).extracting(CompletionItem::getLabel)
            .containsExactlyInAnyOrder("rentalRate", "title", "helper", "combine");
    }

    @Test
    void inertUnderOtherDirectives() {
        String source = "type Foo { x: Int @service(service: {className: \"com.example.FilmFields\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        assertThat(externalField(source, cursor)).isEmpty();
    }

    @Test
    void emptyWhenClassExposesNoLifter() {
        var data = new CompletionData(
            List.of(), List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.NoLifters", "com.example.NoLifters", "",
                List.of(new CompletionData.Method("plain", "String", "", List.of())),
                List.of())));
        String source = "type Foo { x: Int @externalField(reference: {className: \"com.example.NoLifters\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        assertThat(externalField(data, source, cursor)).isEmpty();
    }

    private static List<CompletionItem> externalField(String source, Point cursor) {
        return externalField(DATA, source, cursor);
    }

    private static List<CompletionItem> externalField(CompletionData data, String source, Point cursor) {
        return run(source, cursor, (ctx, directive, bytes) ->
            ExternalFieldCompletions.generate(VOCAB, data, ctx, directive, cursor, bytes));
    }

    private static List<CompletionItem> method(String source, Point cursor) {
        return run(source, cursor, (ctx, directive, bytes) ->
            MethodCompletions.generate(VOCAB, DATA, ctx, directive, cursor, bytes));
    }

    private interface Provider {
        List<CompletionItem> generate(CompletionContext ctx, Directives.Directive directive, byte[] bytes);
    }

    private static List<CompletionItem> run(String source, Point cursor, Provider provider) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = CompletionContext.from(locOpt.get(), bytes);
        return provider.generate(context, directive, bytes);
    }
}
