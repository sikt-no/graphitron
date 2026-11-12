package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.ClasspathClasses;
import no.sikt.graphitron.lsp.facts.ClasspathMethods;
import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where two entries on a graph's classpath declare one class name, the editor answers with the copy
 * the generator resolves the name to, the first entry in classpath order, and never with the
 * shadowed copy or both. The graph reads the classpath corpus and then a library declaring
 * {@code FilmBlurbHolder} again: the corpus's holds a {@code FilmBlurb}, the library's a
 * {@code lib.Blurb}, so which copy answered is visible in the type each surface renders.
 */
class ClasspathOrderResolutionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String DOCUMENT = "scenarios/type-backing-partition-corpus-and-library";

    private static final String HOLDER = "no.sikt.graphitron.rewrite.test.services.FilmBlurbHolder";

    @Test
    @DisplayName("a method on a class two entries declare is the first entry's, once")
    void methodsAreTheFirstEntrysCopy() {
        var methods = FACTS.read(DOCUMENT,
            graph -> ClasspathMethods.named(graph.handle(), HOLDER, "blurb"));

        assertThat(methods).singleElement()
            .satisfies(method -> assertThat(method.signature()).isEqualTo("FilmBlurb blurb()"));
    }

    @Test
    @DisplayName("the slots a class two entries declare offers are the first entry's, once")
    void slotsAreTheFirstEntrysCopy() {
        var slots = FACTS.read(DOCUMENT, graph -> ClassMemberSlots.of(graph.handle(), HOLDER));

        assertThat(slots).singleElement().satisfies(slot -> {
            assertThat(slot.name()).isEqualTo("blurb");
            assertThat(slot.displayType()).isEqualTo("FilmBlurb");
        });
    }

    @Test
    @DisplayName("a class two entries declare is present, and a name neither declares is not")
    void presenceReadsTheGraphsResolution() {
        ClasspathClasses.Presence held = FACTS.read(DOCUMENT,
            graph -> ClasspathClasses.presenceOf(graph.handle(), HOLDER));
        ClasspathClasses.Presence missing = FACTS.read(DOCUMENT,
            graph -> ClasspathClasses.presenceOf(graph.handle(), HOLDER + "Missing"));

        assertThat(held).isEqualTo(ClasspathClasses.Presence.KNOWN);
        assertThat(missing).isEqualTo(ClasspathClasses.Presence.UNKNOWN);
    }
}
