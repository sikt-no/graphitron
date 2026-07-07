package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforcer for the size budget the development-principles document sets on itself (its
 * "Constraints" postscript): the document is a shared context cost, loaded by agents and
 * reviewers on every design consult, so an addition that pushes past the cap must displace
 * something rather than accrete.
 *
 * <p>Words are counted as whitespace-separated tokens ({@code wc -w} semantics), the same
 * coarse measure the budget entry is stated in. Companion to the doc-coverage tests in this
 * package ({@link SealedHierarchyDocCoverageTest} and siblings): same walk-up doc location,
 * but pinning a size invariant rather than a content mapping.
 */
@UnitTier
class DocSizeBudgetTest {

    private static final String DOCS_PATH = "docs/architecture/explanation/development-principles.adoc";

    private static final int WORD_BUDGET = 3_500;

    @Test
    void developmentPrinciplesStaysUnderBudget() throws IOException {
        String doc = Files.readString(locateDoc(), StandardCharsets.UTF_8);
        long words = countWords(doc);
        assertThat(words)
            .as("development-principles.adoc is %d words against its %d-word budget.\n"
                + "The document budgets itself (see its Constraints entry): it is loaded on\n"
                + "every design consult, so per-read cost matters more than completeness.\n"
                + "An addition that pushes past the cap must displace something; move\n"
                + "narratives to their enforcer or their audience's reference page.",
                words, WORD_BUDGET)
            .isLessThanOrEqualTo(WORD_BUDGET);
    }

    private static long countWords(String text) {
        long count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            boolean ws = Character.isWhitespace(text.charAt(i));
            if (!ws && !inWord) count++;
            inWord = !ws;
        }
        return count;
    }

    private static Path locateDoc() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(DOCS_PATH);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException(
            "Could not locate " + DOCS_PATH + " by walking up from " + cwd);
    }
}
