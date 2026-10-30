package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: an emitter opens a transaction in generated code only through
 * {@code OwnedScope}, so every scope graphitron's own fetcher opens carries the mark the emitted
 * provider reads. A write emitter added later that renders {@code transactionResult(} or
 * {@code transaction(} on its own would open an unmarked scope, which the provider treats as a
 * consumer's and reports differently when it fails.
 *
 * <p>Only string, character and text-block literals are scanned, so prose that names the call in a
 * comment does not count. When this guard fires, render the transaction through
 * {@code OwnedScope.transactionResult}.
 */
@UnitTier
class OwnedScopeSeamGuardTest {

    private static final Pattern OPENS_A_TRANSACTION =
        Pattern.compile("\\.(transactionResult|transaction)\\(");

    /** The renderer itself, which is where the call is allowed to be spelled. */
    private static final String SEAM = "OwnedScope.java";

    /** A floor on scanned files, so a drifted root cannot make the guard pass vacuously. */
    private static final int MIN_SCANNED_MAIN_FILES = 200;

    @Test
    void everyGeneratedTransactionIsRenderedThroughTheSeam() throws IOException {
        Path root = GuardScope.locateRepoRoot().resolve("graphitron").resolve("src/main/java");
        List<String> findings = new ArrayList<>();
        int scanned = 0;
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                if (file.getFileName().toString().equals(SEAM)) continue;
                String[] byLine = JavaSourceRegions.strings(Files.readString(file));
                for (int i = 0; i < byLine.length; i++) {
                    if (OPENS_A_TRANSACTION.matcher(byLine[i]).find()) {
                        findings.add(root.relativize(file) + ":" + (i + 1) + "  " + byLine[i].strip());
                    }
                }
            }
        }

        assertThat(scanned).as("the walk reached the generator's main sources").isGreaterThan(MIN_SCANNED_MAIN_FILES);
        assertThat(findings)
            .as("a transaction rendered outside OwnedScope opens an unmarked scope; render it through "
                + "OwnedScope.transactionResult. Offending sites:\n" + String.join("\n", findings))
            .isEmpty();
    }

    @Test
    void theGuardSeesAnUnmarkedTransactionInALiteral() {
        String[] byLine = JavaSourceRegions.strings(
            "class X { String s = \"dsl.transactionResult(tx -> \"; // transactionResult( in prose\n}");
        assertThat(OPENS_A_TRANSACTION.matcher(byLine[0]).find()).isTrue();
    }
}
