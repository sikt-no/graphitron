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
 * The approval over every documentation example rendered from a fact document
 * ({@link ManualExamples}), its schema and the rows it shows, and the floors that keep the queries,
 * the rendered files and the pages talking about the same set.
 *
 * <p>A render that differs from the committed file is left under {@code target/manual-examples}, and the failure gives the {@code cp} that
 * approves it. It lands under {@code target} because the committed files live in the published
 * documentation tree.
 *
 * <p>Four floors, each naming the file and the fix: a query whose rendered file is missing is a
 * render (the approval reports it), a rendered file whose query is gone is a deletion, an example
 * naming a fact document that does not exist is an edit to the query, and an example no page beside
 * its folder includes is an edit to the page.
 */
@PipelineTier
class ManualExampleTest {

    static Stream<Path> queries() {
        return ManualExamples.queries().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queries")
    void theRenderedExampleIsWhatTheFactDocumentHolds(Path query) throws IOException {
        approve(query, ManualExamples.renderedFile(query), ManualExamples.render(query));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queries")
    void theRenderedRowsAreWhatTheFactDocumentAsserts(Path query) throws IOException {
        Path approved = ManualExamples.rowsFile(query);
        var rendered = ManualExamples.renderRows(query);
        if (rendered.isPresent()) {
            approve(query, approved, rendered.get());
        } else {
            assertThat(approved)
                .as("no example in %s shows rows, so the table file renders from nothing. Delete it.",
                    query)
                .doesNotExist();
        }
    }

    private static void approve(Path query, Path approved, String rendered) throws IOException {
        String committed = Files.isRegularFile(approved) ? Files.readString(approved) : null;

        Path left = Path.of("target", "manual-examples")
            .resolve(ManualExamples.docs().relativize(approved));
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
    void everyExampleNamesAFactDocumentThatExists() {
        List<String> unresolved = ManualExamples.queries().stream()
            .flatMap(q -> ManualExamples.examples(q).stream()
                .filter(e -> ManualExamples.factDocument(e.path()).isEmpty())
                .map(e -> q + " " + e.name() + " (" + e.path() + ")"))
            .toList();
        assertThat(unresolved)
            .as("an example's @factDocument(path:) names a fact document by its folder and name, as"
                + " scenarios/result-external-field; these name one that does not exist")
            .isEmpty();
    }

    @Test
    void everyRenderedExampleHasAQuery() {
        List<String> orphans = Stream.concat(ManualExamples.rendered().stream(),
                ManualExamples.renderedRows().stream())
            .filter(r -> !Files.isRegularFile(ManualExamples.queryFile(r)))
            .map(Path::toString)
            .toList();
        assertThat(orphans)
            .as("a rendered example whose query is gone renders from nothing and would go on being"
                + " published. Delete the file.")
            .isEmpty();
    }

    @Test
    void everyExampleIsIncludedByAPage() throws IOException {
        var missing = new java.util.ArrayList<String>();
        for (Path query : ManualExamples.queries()) {
            Path rendered = ManualExamples.renderedFile(query);
            String pages;
            try (Stream<Path> files = Files.list(query.getParent().getParent())) {
                pages = files.filter(p -> p.getFileName().toString().endsWith(".adoc"))
                    .map(ManualExamples::read)
                    .collect(java.util.stream.Collectors.joining("\n"));
            }
            for (var example : ManualExamples.examples(query)) {
                var lines = new java.util.ArrayList<String>();
                lines.add(ManualExamples.includeLine(rendered, example.name()));
                for (String relation : example.relations()) {
                    lines.add(ManualExamples.includeLine(ManualExamples.rowsFile(query),
                        ManualExamples.rowsRegion(example.name(), relation)));
                }
                for (String line : lines) {
                    if (!pages.contains(line)) {
                        missing.add(line + " (" + query + ")");
                    }
                }
            }
        }
        assertThat(missing)
            .as("an example no page includes is one nobody reads. Add the include line where the"
                + " example belongs in a page beside its examples folder.")
            .isEmpty();
    }
}
