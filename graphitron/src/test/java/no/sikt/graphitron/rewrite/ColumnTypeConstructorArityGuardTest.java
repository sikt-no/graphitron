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
 * R446 structural guard. {@code ColumnRef} and {@code JooqCatalog.ColumnEntry} carry a
 * {@code TypeName columnType} decided once at the catalog reflection boundary via
 * {@code TypeName.get(col.getType())}, which decodes array-typed columns natively. Both records
 * also expose a hand-built auxiliary constructor (the 3-arg {@code ColumnRef} / 4-arg
 * {@code ColumnEntry} form) that derives {@code columnType} from the source-form {@code columnClass}
 * string via {@code ClassName.bestGuess}; that path is a <em>test-only</em> convenience, because
 * {@code bestGuess} is the exact call the boundary type-lift exists to remove (it rejects array
 * descriptors) and re-establishes the {@code Class -> String -> re-parse} round-trip the lift cut.
 *
 * <p>Nothing in the type system pins "test-only" (the aux constructors must be public so the
 * cross-package fixtures can reach them), so this guard is the enforcer: production construction in
 * the rewrite source tree must pass the decoded {@code columnType} explicitly (the full-arity form),
 * never route a column type back through the string. A production site that regresses to the
 * short-arity form would silently reintroduce the array-descriptor crash for an array column and,
 * for a placeholder string, a swallowed-to-null {@code columnType}, so it fails here at build time.
 *
 * <p>Detection is line-based (every in-tree construction site is single-line): a
 * {@code new ColumnRef(} / {@code new ColumnEntry(} line must also carry the {@code columnType}
 * marker ({@code .columnType()} threaded from a sibling record, or {@code TypeName.get(} at a
 * reflection site). The scanned-file count is asserted nonzero so a drifted walk root cannot pass
 * vacuously. If a production site ever legitimately spans multiple lines, that is the deliberate
 * review point: inline it or widen this guard, do not silently route a type through the string.
 */
@UnitTier
class ColumnTypeConstructorArityGuardTest {

    private static final Path REWRITE_ROOT = Path.of("src/main/java/no/sikt/graphitron/rewrite");

    /** A construction site of either column-carrying record. */
    private static final Pattern CONSTRUCTION =
        Pattern.compile("new (ColumnRef|ColumnEntry)\\(");

    /** The full-arity marker: an explicit decoded {@code columnType} argument. */
    private static final Pattern CARRIES_COLUMN_TYPE =
        Pattern.compile("\\.columnType\\(\\)|TypeName\\.get\\(");

    @Test
    void productionConstructsColumnRecordsWithExplicitColumnType() throws IOException {
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
                // Skip the auxiliary constructors' own declarations: they legitimately take the
                // short-arity form and delegate. They live on the record types themselves.
                if (line.contains("public ColumnRef(") || line.contains("public ColumnEntry(")) continue;
                if (CONSTRUCTION.matcher(line).find() && !CARRIES_COLUMN_TYPE.matcher(line).find()) {
                    violations.add(file + ":" + (i + 1) + "  " + line.strip());
                }
            }
        }

        assertThat(javaFiles)
            .as("the guard must scan the rewrite source tree recursively; a zero .java count means "
                + "the walk root drifted and the guard would pass vacuously")
            .isNotEmpty();

        assertThat(violations)
            .as("production must construct ColumnRef / ColumnEntry with the decoded columnType "
                + "(from a sibling record's .columnType() or TypeName.get(col.getType()) at the "
                + "reflection boundary), never via the test-only string-decoding auxiliary "
                + "constructor — that would reintroduce the R446 array-column bestGuess crash. "
                + "Offending sites:\n" + String.join("\n", violations))
            .isEmpty();
    }
}
