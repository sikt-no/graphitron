package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R316 slice 5 — the thoroughness gate's remnant backstop. The pivot retired the
 * {@code carrier x intent x mapping} model; slices 2-4 deleted the four dimension types
 * ({@code Carrier}, {@code Intent}, {@code Mapping}, {@code SourceCardinality}) and the
 * {@code Mapping.TableConnection} value (decomposed into {@code Single(Connection)} + a read
 * operation). The mandate is that the old model is <em>gone</em>, not merely shadowed by the new one.
 *
 * <p>This is a source-file scan, the same shape {@code UnifiedEmissionPinsTest} uses, and it is a
 * deliberately narrow <strong>type-resurrection</strong> guard, not a prose grep. Per the R316 spec
 * ("The grep is not a coverage check and must not be relied on as one"), exhaustiveness is the
 * coverage gate's job ({@code ClassifiedDslTest.everyDimensionValueIsExercised}, the disjoint partition
 * over the {@code source} / {@code Operation} seals); the explanatory-prose sweep is the docs and
 * test-comment passes. This guard's single job is to fail the build if a retired dimension <em>type</em>
 * comes back. It keys on imports and whole-word references rather than bare-word prose so it cannot fire
 * on the legitimate historical references the model javadoc keeps (for example "the fused
 * {@code TableConnection} mapping decomposed into {@code Single(Connection)}", which documents the new
 * model by naming what it replaced).
 *
 * <ul>
 *   <li>{@link #retiredTypeSourceFilesAreDeleted()} — none of the four type files exists under the tree.</li>
 *   <li>{@link #noSourceFileImportsARetiredModelType()} — nothing imports a retired type from the model
 *       package, the only way (outside the model package itself) to reference a resurrected one.</li>
 *   <li>{@link #distinctiveRetiredNamesAppearNowhere()} — the two distinctive names {@code Intent} and
 *       {@code SourceCardinality} (which collide with no live identifier or English word, unlike
 *       {@code Carrier} / {@code Mapping}) appear nowhere as whole words, catching both a resurrected
 *       type and any stale prose that still names them.</li>
 *   <li>{@link #carveOutTypesSurvive()} — the deliberately-kept neighbours ({@code SourceShape},
 *       {@code LookupMapping}, {@code MappingEntry}) still exist, so the guard cannot be satisfied by
 *       over-deletion.</li>
 * </ul>
 */
@UnitTier
class RetiredDimensionTypesAreGoneTest {

    private static final List<Path> SOURCE_ROOTS =
        List.of(Path.of("src/main/java"), Path.of("src/test/java"));

    /** The four dimension types deleted by R316 (their source files must not exist). */
    private static final List<String> RETIRED_TYPE_FILES =
        List.of("Carrier.java", "Intent.java", "Mapping.java", "SourceCardinality.java");

    /**
     * Retired type simple names for the import guard. {@code Carrier} / {@code Mapping} are listed here
     * (an import is an unambiguous type reference) but deliberately not bare-word-grepped, because both
     * collide with live identifiers ({@code LookupMapping}, {@code MappingEntry}, {@code ColumnMapping})
     * and ordinary prose ("carrier-side", "carrier classifier").
     */
    private static final Pattern RETIRED_IMPORT = Pattern.compile(
        "import\\s+no\\.sikt\\.graphitron\\.rewrite\\.model\\.(Carrier|Intent|Mapping|SourceCardinality)\\s*;");

    /** Names distinctive enough that a whole-word match is always a retired reference, never prose. */
    private static final List<String> DISTINCTIVE_RETIRED_NAMES = List.of("Intent", "SourceCardinality");

    /** Kept-by-design neighbours the deletions must not have swept (the spec's explicit carve-outs). */
    private static final List<String> CARVE_OUT_FILES =
        List.of("SourceShape.java", "LookupMapping.java", "MappingEntry.java");

    @Test
    void retiredTypeSourceFilesAreDeleted() {
        var found = new TreeSet<String>();
        forEachJavaFile(p -> {
            if (RETIRED_TYPE_FILES.contains(p.getFileName().toString())) found.add(p.toString());
        });
        assertThat(found)
            .as("the retired dimension type files must be deleted (R316 folded carrier/intent/mapping/"
                + "sourceCardinality into the source/operation/target hierarchies)")
            .isEmpty();
    }

    @Test
    void noSourceFileImportsARetiredModelType() {
        var offenders = new TreeSet<String>();
        forEachJavaFile(p -> {
            if (isThisGuard(p)) return; // names the retired types in its own patterns
            if (RETIRED_IMPORT.matcher(readString(p)).find()) offenders.add(p.toString());
        });
        assertThat(offenders)
            .as("no source file may import a retired dimension type from the model package; a resurrected "
                + "type can only be referenced (outside its own package) through such an import")
            .isEmpty();
    }

    @Test
    void distinctiveRetiredNamesAppearNowhere() {
        var offenders = new TreeSet<String>();
        var patterns = DISTINCTIVE_RETIRED_NAMES.stream()
            .map(n -> Pattern.compile("\\b" + n + "\\b"))
            .toList();
        forEachJavaFile(p -> {
            if (isThisGuard(p)) return; // necessarily names the retired types
            var text = readString(p);
            for (var pat : patterns) {
                if (pat.matcher(text).find()) offenders.add(pat.pattern() + " in " + p);
            }
        });
        assertThat(offenders)
            .as("the distinctive retired names (Intent, SourceCardinality) must not survive as whole words "
                + "anywhere — neither as a resurrected type nor as stale explanatory prose")
            .isEmpty();
    }

    @Test
    void carveOutTypesSurvive() {
        var present = new TreeSet<String>();
        forEachJavaFile(p -> {
            if (CARVE_OUT_FILES.contains(p.getFileName().toString())) present.add(p.getFileName().toString());
        });
        assertThat(present)
            .as("the carve-out types kept by design must still exist; the remnant guard must not be "
                + "satisfiable by over-deleting SourceShape / LookupMapping / MappingEntry")
            .containsAll(CARVE_OUT_FILES);
    }

    private static boolean isThisGuard(Path p) {
        return p.getFileName().toString().equals("RetiredDimensionTypesAreGoneTest.java");
    }

    private static void forEachJavaFile(java.util.function.Consumer<Path> action) {
        for (var root : SOURCE_ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(action);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static String readString(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
