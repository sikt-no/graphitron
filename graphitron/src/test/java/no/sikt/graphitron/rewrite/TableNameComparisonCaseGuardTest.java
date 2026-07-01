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
 * <p>R358 Phase 2 moves the canonical identity comparison onto the type
 * ({@link no.sikt.graphitron.rewrite.model.TableRef#sameTable(String)} /
 * {@code denotesSameTableAs(TableRef)}), so every consumer routes through one case-insensitive
 * predicate instead of re-deriving the case-folding contract the jOOQ catalog already guarantees.
 * This guard is then a backstop on a predicate that is correct by construction: it forbids
 * <em>any</em> raw {@code tableName()} comparison, either operand orientation, either case-sensitivity.
 * The single legitimate home for a raw comparison is the predicate's own body in
 * {@code model/TableRef.java}, excluded here (the {@code UnifiedEmissionPinsTest}
 * "exclude the unified emitter itself" pattern).
 *
 * <p>The scan is orientation-complete and spelling-closed for the comparison mode, not a total
 * invariant: other spellings ({@code Objects.equals}, {@code ==}, {@code Set.contains}) and the
 * lookup-key consumption mode are out of scope (see R358's "Scope and residual blind spots").
 *
 * <p>The scanned-file count is asserted nonzero: a copy of the precedent's
 * ({@code UnifiedEmissionPinsTest}) non-recursive {@code Files.list} over the wrong subtree would
 * scan zero {@code .java} files and pass vacuously, an enforced-but-checking-nothing guard.
 *
 * <p>If a raw {@code tableName()} comparison is ever legitimately required, it belongs on
 * {@code TableRef} (excluded here); touching this assertion is the deliberate architectural review
 * point the guard exists to create.
 */
@UnitTier
class TableNameComparisonCaseGuardTest {

    private static final Path REWRITE_ROOT = Path.of("src/main/java/no/sikt/graphitron/rewrite");

    /** The predicate's home: the one file allowed to compare {@code tableName} directly. */
    private static final Path PREDICATE_HOME = Path.of("model", "TableRef.java");

    /**
     * Left-operand {@code X.tableName().equals(...)} / {@code .equalsIgnoreCase(...)}. No trailing
     * {@code (}, so both the case-sensitive footgun and its case-insensitive sibling trip.
     */
    private static final Pattern LEFT_OPERAND =
        Pattern.compile("\\.tableName\\(\\)\\s*\\.equals");

    /**
     * Right-operand {@code someString.equals[IgnoreCase](... .tableName())}, bounded to one statement
     * by {@code [^;\n]*} so it never spans into the next call.
     */
    private static final Pattern RIGHT_OPERAND =
        Pattern.compile("\\.equals(IgnoreCase)?\\([^;\\n]*\\.tableName\\(\\)");

    @Test
    void noRawTableNameComparison() throws IOException {
        var scan = scan(LEFT_OPERAND, RIGHT_OPERAND);

        assertThat(scan.scannedFiles())
            .as("the guard must scan the rewrite source tree recursively; a zero .java count means "
                + "the walk root drifted (e.g. a non-recursive list over the wrong subtree) and the "
                + "guard would pass vacuously")
            .isPositive();

        assertThat(scan.violations())
            .as("TableRef.tableName() is the case-preserved verbatim @table echo; the same logical "
                + "table can surface in two casings, so a raw tableName() comparison silently "
                + "mis-decides under an UPPERCASE @table over a lowercase jOOQ catalog (the R357 bug). "
                + "Compare table identity through TableRef.sameTable(String) / "
                + "denotesSameTableAs(TableRef) instead. Offending sites:\n"
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
                .filter(p -> !p.endsWith(PREDICATE_HOME))
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
