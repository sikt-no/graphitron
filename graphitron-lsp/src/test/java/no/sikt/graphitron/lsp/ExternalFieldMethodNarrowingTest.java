package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.MethodCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import org.eclipse.lsp4j.CompletionItem;
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
 * Under {@code @externalField} the method list narrows to that directive's contract, a lifter
 * returning a jOOQ {@code Field} from a single parameter, where any other directive's method slot
 * offers the class's whole method list. Same coordinate, same census, different answer.
 *
 * <p>These were two providers chained by dispatch order, with the narrowing in one of them and the
 * fall-through in the chaining. Both read the same class census, so the pairing was expressing inside
 * a provider list what the arm can state for itself; the cases live on because the behaviour did, and
 * one of them changed shape in the collapse: a class exposing no lifter used to make the narrowing
 * provider return empty so the generic one could answer, and now the arm falls back to the whole list
 * itself. The observable answer is the same, and it is asserted in one place instead of two.
 */
class ExternalFieldMethodNarrowingTest {

    private static final LspVocabulary VOCAB = BundledVocabulary.get();

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofClasspath(tmp, List.of(
            StoreFixture.jarClass("com.example.FilmFields", List.of(
                // External-field lifters: Field<X> name(Table), single parameter, Field return.
                StoreFixture.method("rentalRate", "Field", StoreFixture.parameter("film", "Film")),
                StoreFixture.method("title", "Field", StoreFixture.parameter("film", "Film")),
                // Not a lifter: wrong return type.
                StoreFixture.method("helper", "String", StoreFixture.parameter("film", "Film")),
                // Not a lifter: wrong arity.
                StoreFixture.method("combine", "Field",
                    StoreFixture.parameter("a", "Film"), StoreFixture.parameter("b", "Film")))),
            StoreFixture.jarClass("com.example.NoLifters", List.of(
                StoreFixture.method("plain", "String")))));
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void externalFieldNarrowsToFieldReturningSingleArgMethods() {
        String source = "type Foo { x: Int @externalField(reference: {className: \"com.example.FilmFields\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        assertThat(complete(source, cursor)).extracting(CompletionItem::getLabel)
            .containsExactlyInAnyOrder("rentalRate", "title");
    }

    @Test
    void anotherDirectivesMethodSlotOffersEveryMethodOfTheSameClass() {
        String source = "type Foo { x: Int @service(service: {className: \"com.example.FilmFields\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        assertThat(complete(source, cursor)).extracting(CompletionItem::getLabel)
            .containsExactlyInAnyOrder("rentalRate", "title", "helper", "combine");
    }

    @Test
    void aClassWithNoLifterFallsBackToItsWholeMethodList() {
        // Deliberate: an author on a class that cannot lift a field is better served by seeing what
        // it does have than by an empty popup.
        String source = "type Foo { x: Int @externalField(reference: {className: \"com.example.NoLifters\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));

        assertThat(complete(source, cursor)).extracting(CompletionItem::getLabel)
            .containsExactly("plain");
    }

    private static List<CompletionItem> complete(String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = CompletionContext.from(locOpt.get(), bytes);
        return MethodCompletions.generate(VOCAB, store.handle(), context, directive, cursor, bytes);
    }
}
