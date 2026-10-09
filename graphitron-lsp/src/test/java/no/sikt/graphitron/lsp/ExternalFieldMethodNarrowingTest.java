package no.sikt.graphitron.lsp;

import no.sikt.graphitron.model.test.ClasspathCorpus;
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
 * Under {@code @externalField} the method list narrows to the methods that directive may name, where
 * any other directive's method slot offers the class's whole method list. Same coordinate, same
 * census, different answer.
 *
 * <p>Which methods those are is the lifter arm's answer rather than a shape this provider reads off
 * the census, and {@link #aFieldReturningOneArgMethodIsNotOfferedWhenItsArgumentIsNoTable} is the
 * case that distinguishes the two. It is the shape a lifter has, and it is not a lifter: its
 * argument is not a table, so the generator refuses it and the arm never admitted it. The reading
 * this replaces offered it, because an arity and a return type's simple name is the whole of what a
 * census can be asked.
 *
 * <p>The capture writes both halves, as a run does: the census rows say what each class declares,
 * and the arm rows say which of them the arm admitted. A method declared and not admitted is the
 * interesting row, and every non-lifter here is one.
 */
class ExternalFieldMethodNarrowingTest {

    private static final LspVocabulary VOCAB = BundledVocabulary.get();

    /**
     * Two lifters, {@code rentalRate} and {@code title}, beside one non-lifter per clause of the
     * contract: a wrong return type, a wrong arity, and a lifter's shape exactly whose argument is no
     * table.
     */
    private static final String FIELDS_CLASS = "no.sikt.graphitron.rewrite.test.extensions.FilmFields";

    /** A class declaring one method and no lifter. */
    private static final String NO_LIFTERS = "no.sikt.graphitron.rewrite.test.services.FilmShelfService";

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.held().ofClasspath(tmp, List.of(ClasspathCorpus.service()));
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void externalFieldNarrowsToTheMethodsTheArmAdmitted() {
        assertThat(completeMethodsOn(FIELDS_CLASS, "externalField", "reference"))
            .containsExactlyInAnyOrder("rentalRate", "title");
    }

    /**
     * The clause a census cannot reach. One argument in and a {@code Field} out is a lifter's whole
     * observable shape, so the reading this replaces offered {@code fromName} and the author got a
     * suggestion that fails to bind at build.
     */
    @Test
    void aFieldReturningOneArgMethodIsNotOfferedWhenItsArgumentIsNoTable() {
        assertThat(completeMethodsOn(FIELDS_CLASS, "externalField", "reference"))
            .doesNotContain("fromName");
    }

    @Test
    void anotherDirectivesMethodSlotOffersEveryMethodOfTheSameClass() {
        assertThat(completeMethodsOn(FIELDS_CLASS, "service", "service"))
            .containsExactlyInAnyOrder("rentalRate", "title", "helper", "combine", "fromName");
    }

    /**
     * Deliberate: an author on a class that cannot lift a field is better served by seeing what it
     * does have than by an empty popup.
     */
    @Test
    void aClassWithNoLifterFallsBackToItsWholeMethodList() {
        assertThat(completeMethodsOn(NO_LIFTERS, "externalField", "reference"))
            .containsExactly("shelf");
    }

    private static List<String> completeMethodsOn(String classFqn, String directive, String argument) {
        String source = "type Foo { x: Int @" + directive + "(" + argument
            + ": {className: \"" + classFqn + "\", method: \"\"}) }\n";
        Point cursor = new Point(0, source.lastIndexOf('"'));
        return complete(source, cursor).stream().map(CompletionItem::getLabel).toList();
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
