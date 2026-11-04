package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval over every manual example rendered from a fact document ({@link ManualExamples}), and
 * the floors that keep the queries, the rendered files and the pages talking about the same set.
 *
 * <p>The approval idiom is {@link CorpusFragmentTest}'s: a render that differs from the committed
 * file is left under {@code target/manual-examples}, and the failure gives the {@code cp} that
 * approves it. It lands under {@code target} because the committed files live in the published
 * documentation tree.
 *
 * <p>Four floors, each naming the file and the fix: a query whose rendered file is missing is a
 * render (the approval reports it), a rendered file whose query is gone is a deletion, a query naming
 * no fact document or one that does not exist is an edit to the query, and a rendered file no page
 * beside its folder includes is an edit to the page.
 */
@PipelineTier
class ManualExampleTest {

    static Stream<Path> queries() {
        return ManualExamples.queries().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queries")
    void theRenderedExampleIsWhatTheFactDocumentHolds(Path query) throws IOException {
        Path approved = ManualExamples.renderedFile(query);
        String rendered = ManualExamples.render(query);
        String committed = Files.isRegularFile(approved) ? Files.readString(approved) : null;

        Path left = Path.of("target", "manual-examples")
            .resolve(ManualExamples.manual().relativize(approved));
        if (rendered.equals(committed)) {
            Files.deleteIfExists(left);
            return;
        }
        Files.createDirectories(left.getParent());
        Files.writeString(left, rendered);

        assertThat(committed)
            .as("the committed example for '%s' must be exactly what its query renders over the fact"
                + " document it names. This run's render is in %s; diff it against the approved file,"
                + " and if the new output is right, copy it over:%n%n  cp %s %s%n",
                query, left, left, approved)
            .isEqualTo(rendered);
    }

    @Test
    void theManualHasAnExample() {
        assertThat(ManualExamples.queries())
            .as("a sweep over no examples would pass whatever the manual says")
            .isNotEmpty();
    }

    @Test
    void everyQueryNamesAFactDocumentThatExists() {
        List<String> unresolved = ManualExamples.queries().stream()
            .filter(q -> ManualExamples.factDocumentPath(ManualExamples.read(q))
                .flatMap(ManualExamples::factDocument).isEmpty())
            .map(Path::toString)
            .toList();
        assertThat(unresolved)
            .as("a query must carry @factDocument(path:) naming a fact document by its folder and"
                + " name, as scenarios/result-external-field; these name none, or one that does not"
                + " exist")
            .isEmpty();
    }

    @Test
    void everyRenderedExampleHasAQuery() {
        List<String> orphans = ManualExamples.rendered().stream()
            .filter(r -> !Files.isRegularFile(ManualExamples.queryFile(r)))
            .map(Path::toString)
            .toList();
        assertThat(orphans)
            .as("a rendered example whose query is gone renders from nothing and would go on being"
                + " published. Delete the file.")
            .isEmpty();
    }

    @Test
    void everyRenderedExampleIsIncludedByAPage() throws IOException {
        var missing = new java.util.ArrayList<String>();
        for (Path rendered : ManualExamples.rendered()) {
            Path pageDirectory = rendered.getParent().getParent();
            String line = ManualExamples.includeLine(rendered);
            boolean included;
            try (Stream<Path> pages = Files.list(pageDirectory)) {
                included = pages.filter(p -> p.getFileName().toString().endsWith(".adoc"))
                    .anyMatch(p -> ManualExamples.read(p).contains(line));
            }
            if (!included) {
                missing.add(rendered + " (" + line + ")");
            }
        }
        assertThat(missing)
            .as("a rendered example no page includes is an example nobody reads. Add the include"
                + " line where the example belongs in a page beside its examples folder.")
            .isEmpty();
    }
}
