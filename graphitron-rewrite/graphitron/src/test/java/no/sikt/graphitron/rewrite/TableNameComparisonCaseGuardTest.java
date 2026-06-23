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
 * R358 structural guard. {@link no.sikt.graphitron.rewrite.model.TableRef#tableName()} is the
 * case-preserved verbatim {@code @table(name:)} echo, so the <em>same</em> logical table can surface
 * as two differently-cased {@code tableName()} strings: the verbatim {@code @table} casing on one
 * operand, the lowercase jOOQ {@code Table.getName()} casing the record-class resolution path feeds
 * in on the other. Comparing those strings with case-sensitive {@code .equals} silently mis-decides
 * under an Oracle-style UPPERCASE {@code @table} over a lowercase jOOQ catalog: that was the R357 bug
 * ({@code collectAccessorMatches} dropping a record-composite carrier child), and
 * {@code FieldBuilder.resolveCarrierIdEncoder} was one explicit {@code @nodeId(typeName:)} hop from
 * the same defect.
 *
 * <p>The guard scans the rewrite source tree and forbids the case-sensitive
 * {@code tableName().equals(...)} spelling. The established comparison sites use
 * {@code equalsIgnoreCase}, which {@code .equals(} does not match, so they pass untouched. This is a
 * tripwire for the spelling that bit, not a proof that no same-table comparison can ever drift (see
 * R358's "Scope and residual blind spots"). R358 Phase 2 routes every comparison through
 * {@code TableRef.sameTable} / {@code denotesSameTableAs} and tightens this scan to a structural
 * backstop.
 *
 * <p>The scanned-file count is asserted nonzero: a copy of the precedent's
 * ({@code UnifiedEmissionPinsTest}) non-recursive {@code Files.list} over the wrong subtree would
 * scan zero {@code .java} files and pass vacuously, an enforced-but-checking-nothing guard.
 *
 * <p>If a case-sensitive comparison is ever legitimately required, touching this assertion is the
 * deliberate architectural review point the guard exists to create.
 */
@UnitTier
class TableNameComparisonCaseGuardTest {

    private static final Path REWRITE_ROOT = Path.of("src/main/java/no/sikt/graphitron/rewrite");

    /**
     * Case-sensitive left-operand {@code X.tableName().equals(...)}. The trailing {@code (} excludes
     * {@code equalsIgnoreCase(}, the idiom every other table-name comparison site uses.
     */
    private static final Pattern CASE_SENSITIVE_LEFT =
        Pattern.compile("\\.tableName\\(\\)\\s*\\.equals\\(");

    @Test
    void noCaseSensitiveTableNameComparison() throws IOException {
        var scan = scan(CASE_SENSITIVE_LEFT);

        assertThat(scan.scannedFiles())
            .as("the guard must scan the rewrite source tree recursively; a zero .java count means "
                + "the walk root drifted (e.g. a non-recursive list over the wrong subtree) and the "
                + "guard would pass vacuously")
            .isPositive();

        assertThat(scan.violations())
            .as("TableRef.tableName() is the case-preserved verbatim @table echo; comparing it with "
                + "case-sensitive .equals silently mis-decides on the same logical table under an "
                + "UPPERCASE @table over a lowercase jOOQ catalog (the R357 bug). Compare with "
                + "equalsIgnoreCase (or TableRef.sameTable once R358 Phase 2 lands). Offending sites:\n"
                + String.join("\n", scan.violations()))
            .isEmpty();
    }

    private record ScanResult(int scannedFiles, List<String> violations) {}

    private static ScanResult scan(Pattern... forbidden) throws IOException {
        List<Path> javaFiles;
        try (var paths = Files.walk(REWRITE_ROOT)) {
            javaFiles = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        }
        var violations = new ArrayList<String>();
        for (Path file : javaFiles) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (Pattern pattern : forbidden) {
                    if (pattern.matcher(line).find()) {
                        violations.add(file + ":" + (i + 1) + "  " + line.strip());
                        break; // one report per line even when several patterns hit (e.g. ref-vs-ref)
                    }
                }
            }
        }
        return new ScanResult(javaFiles.size(), violations);
    }
}
