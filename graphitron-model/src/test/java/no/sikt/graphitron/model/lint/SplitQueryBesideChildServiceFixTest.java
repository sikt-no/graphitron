package no.sikt.graphitron.model.lint;

import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deletion offered beside {@code splitquery-redundant-on-service-child}, applied to the fact
 * document {@code scenarios/splitquery-beside-child-service}'s text: it removes exactly the directive
 * the finding names, and nothing else on the line.
 */
class SplitQueryBesideChildServiceFixTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String DOCUMENT = "scenarios/splitquery-beside-child-service";

    @Test
    @DisplayName("the fix deletes the @splitQuery the finding is at, and the line keeps the rest")
    void theFixDeletesTheDirective() {
        var finding = FACTS.read(DOCUMENT, graph -> LintFindings.of(graph.handle())).stream()
            .filter(f -> f.rule() == LintRule.SPLITQUERY_REDUNDANT_ON_SERVICE_CHILD)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no finding of the rule in the document"));
        assertThat(finding.message()).startsWith("Film.rated: ");

        var edit = finding.fix().orElseThrow(() -> new AssertionError("no fix offered")).edits()
            .getFirst();
        String line = text().split("\n", -1)[edit.start().getLine() - 1];
        String edited = line.substring(0, edit.start().getColumn() - 1) + edit.replacement()
            + line.substring(edit.end().getColumn() - 1);
        assertThat(line).contains("rated: String @splitQuery");
        assertThat(edited.trim()).isEqualTo("rated: String");
    }

    private static String text() {
        try {
            return Files.readString(Path.of("src", "test", "resources", "facts", DOCUMENT + ".graphqls"),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
