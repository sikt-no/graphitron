package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fact tier names no javapoet. Capture reads a consumer's sources and writes down what it
 * found; how a name it wrote down is spelled into a source file is the emitting tier's decision,
 * made in {@link CatalogRefs} and {@code render.CatalogRefs}. The store agrees, holding every
 * class name as a {@code VARCHAR}, so a ref built from a live catalog and a ref read back out of
 * the store are the same value.
 *
 * <p>A stand-in for a module boundary until the fact tier moves into {@code graphitron-model}, at
 * which point the pom carries the rule and this test's file list becomes the move's own manifest.
 * The list is spelled out rather than derived: it is the plan's own move set, and a file joining it
 * is a decision somebody should make deliberately.
 *
 * <p>What a violation means concretely: a ref that carries a javapoet type cannot cross the module
 * line, because the emit library sits above it. The fix is never to add the dependency downward. It
 * is to carry the name the catalog reported and lift it where it is emitted.
 */
@UnitTier
class FactTierJavapoetBoundaryTest {

    private static final Path REWRITE = Path.of("src/main/java/no/sikt/graphitron/rewrite");

    /** Whole packages that move, minus the three files the plan's census excludes. */
    private static final Set<String> MOVING_PACKAGES =
        Set.of("capture", "derive", "selection", "schema", "diagnostics", "session");

    /** Excluded from the moving packages: these read the walked model and stay above the line. */
    private static final Set<String> STAYS = Set.of(
        "derive/ClaimDomain.java",
        "derive/DemandResidue.java",
        "schema/federation/EntityResolutionBuilder.java",
        "session/SessionHooks.java",
        "session/SessionStateWarnings.java");

    /** Individually named movers outside those packages. */
    private static final Set<String> MOVING_FILES = Set.of(
        "JooqCatalog.java",
        "RewriteContext.java",
        "ValidationError.java",
        "RejectionKind.java",
        "NodeDeclaration.java",
        "ArgMappingSigil.java",
        "ClasspathEntry.java",
        "ValidationFailedException.java",
        "SchemaParseException.java",
        "BuildWarning.java",
        "catalog/ClasspathScanner.java",
        "catalog/CompletionData.java",
        "compile/CompileFacts.java",
        "compile/CompileDiagnostic.java",
        "compile/CompileRound.java",
        "dependency/DependencyVersions.java",
        "lint/LintConfig.java",
        "lint/LintRule.java",
        "lint/LintFix.java",
        "lint/DeprecationRecognizer.java",
        "model/ColumnRef.java",
        "model/TableRef.java",
        "model/ForeignKeyRef.java",
        "model/Rejection.java",
        "model/ConnectionNaming.java");

    @Test
    void nothingInTheFactTierNamesJavapoet() throws IOException {
        List<Path> moving;
        try (var paths = Files.walk(REWRITE)) {
            moving = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(FactTierJavapoetBoundaryTest::moves)
                .sorted()
                .toList();
        }

        assertThat(moving)
            .as("the move set must be found on disk; an empty walk would pass vacuously")
            .isNotEmpty();

        var violations = new ArrayList<String>();
        for (Path file : moving) {
            if (Files.readString(file).contains("no.sikt.graphitron.javapoet")) {
                violations.add(REWRITE.relativize(file).toString());
            }
        }

        assertThat(violations)
            .as("fact-tier files naming the emit library; carry the captured name instead and lift"
                + " it through CatalogRefs at the site that emits")
            .isEmpty();
    }

    private static boolean moves(Path file) {
        String rel = REWRITE.relativize(file).toString().replace('\\', '/');
        if (STAYS.contains(rel)) {
            return false;
        }
        return MOVING_FILES.contains(rel)
            || MOVING_PACKAGES.contains(rel.substring(0, Math.max(rel.indexOf('/'), 0)));
    }
}
