package no.sikt.graphitron.model.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertion files: claims about a fact document stated beside it, which name the document, only add
 * to what it states, and are checked against its graph.
 */
class FactAssertionsTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String DOCUMENT = "scenarios/field-key-row";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a file's claims are blocks on the document's graph, checked against it")
    void claimsAreCheckedAgainstTheDocumentsGraph() throws IOException {
        var file = FactAssertions.read(write("holds.graphqls", """
            extend schema @factDocument(path: "scenarios/field-key-row")

            extend schema @assertContains(relation: "graphitron_occurrence_producer_lift", rows: \"""
              coordinate,                      read
              Query.filmDetailsBatch.language, COLUMNS
              \""")

            extend schema @assertAbsent(relation: "graphitron_occurrence_producer_lift", rows: \"""
              coordinate,                      read
              Query.filmDetailsBatch.language, ACCESSOR
              \""")
            """));
        assertThat(file.document()).isEqualTo(DOCUMENT);
        assertThat(file.refusals(Set.of(DOCUMENT))).isEmpty();
        assertThat(file.blocks()).extracting(CorpusExpectations.Block::graph).containsOnly(DOCUMENT);
        assertThat(divergences(file)).isEmpty();
    }

    @Test
    @DisplayName("a claim the graph does not hold diverges")
    void aFalseClaimDiverges() throws IOException {
        var file = FactAssertions.read(write("false.graphqls", """
            extend schema @factDocument(path: "scenarios/field-key-row")

            extend schema @assertContains(relation: "graphitron_occurrence_producer_lift", rows: \"""
              coordinate,                      read
              Query.filmDetailsBatch.language, ACCESSOR
              \""")
            """));
        assertThat(divergences(file)).singleElement().asString().contains("NOT_PRODUCED");
    }

    @Test
    @DisplayName("a file stating a whole population is refused, since that is the document's statement")
    void wholePopulationsAreRefused() throws IOException {
        var file = FactAssertions.read(write("whole.graphqls", """
            extend schema @factDocument(path: "scenarios/field-key-row")
            extend schema @assertEmpty(relation: "graphitron_entry_defect")
            extend schema @expectEquals(relation: "graphitron_occurrence_producer_lift", rows: \"""
              coordinate
              Query.filmDetailsBatch.language
              \""")
            """));
        assertThat(file.refusals(Set.of(DOCUMENT)))
            .hasSize(2)
            .anySatisfy(refusal -> assertThat(refusal).contains("@assertEmpty"))
            .anySatisfy(refusal -> assertThat(refusal).contains("@expectEquals"));
    }

    @Test
    @DisplayName("a file naming no document, or one that does not exist, is refused")
    void unnamedAndMissingDocumentsAreRefused() throws IOException {
        var unnamed = FactAssertions.read(write("unnamed.graphqls", """
            extend schema @assertAbsent(relation: "graphitron_field", rows: \"""
              type_name, field_name
              Film,      nothing
              \""")
            """));
        assertThat(unnamed.refusals(Set.of(DOCUMENT))).singleElement().asString().contains("names no fact document");

        var missing = FactAssertions.read(write("missing.graphqls", """
            extend schema @factDocument(path: "scenarios/no-such-document")
            """));
        assertThat(missing.refusals(Set.of(DOCUMENT))).singleElement().asString().contains("does not exist");
    }

    @Test
    @DisplayName("a module's files are every graphqls file under its fact-assertions folder")
    void filesAreReadFromTheFolder() throws IOException {
        write("a/one.graphqls", "extend schema @factDocument(path: \"scenarios/field-key-row\")\n");
        write("two.graphqls", "extend schema @factDocument(path: \"scenarios/field-key-row\")\n");
        write("notes.txt", "not an assertion file");
        assertThat(FactAssertions.in(tmp)).extracting(file -> tmp.relativize(file.file()).toString())
            .containsExactly("a/one.graphqls", "two.graphqls");
        assertThat(FactAssertions.in(tmp.resolve("absent"))).isEmpty();
    }

    private List<String> divergences(FactAssertions.AssertionFile file) {
        return FACTS.read(file.document(), graph ->
            CorpusExpectations.divergences(graph.handle().dsl(), file.blocks()).stream()
                .map(Object::toString).toList());
    }

    private Path write(String name, String text) throws IOException {
        var file = tmp.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }
}
