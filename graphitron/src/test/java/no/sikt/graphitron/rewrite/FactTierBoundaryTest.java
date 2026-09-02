package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fact tier is separable: nothing in the set of files that moves into {@code graphitron-model}
 * reaches upward into the generator. A stand-in for the module boundary until the move happens, at
 * which point the pom carries the rule and this file list becomes the move's own manifest. The list
 * is spelled out rather than derived: it is the plan's move set, and a file joining it is a
 * decision somebody should make deliberately.
 *
 * <p>Four properties. Three are about what moves and can be broken three ways, only the first of
 * them visible to a reader scanning imports; the fourth is about what stays, and is the direction
 * the whole move exists to establish.
 *
 * <ul>
 *   <li><b>No javapoet.</b> Capture reads a consumer's sources and writes down what it found; how
 *       a name it wrote down is spelled into a source file is the emitting tier's decision, made in
 *       {@code render.CatalogRefs}. The store agrees, holding every class name as a
 *       {@code VARCHAR}, so a ref built from a live catalog and a ref read back out of the store
 *       are the same value. The fix for a violation is never to add the dependency downward: it is
 *       to carry the name the catalog reported and lift it where it is emitted.</li>
 *   <li><b>No upward import.</b> The move set may import the destination module
 *       ({@code no.sikt.graphitron.model}, the store and its generated jOOQ classes), itself, and
 *       libraries. graphql-java is a positive allowance and not a gap in the rules: the consumer's
 *       schema is one of the three things capture reads, so a module that defines what a schema
 *       fact is cannot sensibly be unable to parse a schema.</li>
 *   <li><b>No seal straddling the line.</b> A sealed type's permitted subclasses must be in the
 *       same package when the declaring class is in an unnamed module, which every module here is
 *       (no {@code module-info}). So a sealed type that moves while one of its arms stays behind is
 *       a compile error at move time, not a style question, and an import scan cannot see it
 *       because the arms sit in the declaring type's own package. This check is why the move set
 *       carries the whole {@link no.sikt.graphitron.rewrite.model.Rejection} closure rather than
 *       {@code Rejection} alone.</li>
 *   <li><b>Nothing that stays writes.</b> The generator reads facts and writes none, which is what
 *       the move is for rather than a consequence of it. It holds today for a reason worth stating
 *       because it makes the rule cheap to keep: no value the generator computes reaches the store
 *       at all. Capture is handed the registry as it stood before the synthesis rewrites, and it
 *       re-runs the {@code @asConnection} expansion from its own decoded rows rather than
 *       inheriting the pipeline's, so even work the generator has already done is refused. The
 *       plugin's own writers are a separate matter and stay outside this module: they record a
 *       completed pass, a compile round and the consumer's source tree, which are the plugin's
 *       observations rather than the generator's account of itself.</li>
 * </ul>
 */
@UnitTier
class FactTierBoundaryTest {

    private static final Path REWRITE = Path.of("src/main/java/no/sikt/graphitron/rewrite");

    /**
     * The store's write surface, in the three spellings a writer cannot avoid: the generated table
     * constants a statement names, the sink every capture write goes through, and the boot package
     * that opens a store to write to. A read needs none of the three, going through
     * {@link no.sikt.graphitron.model.read.StoreHandle} and the query classes in the move set, so
     * naming any of them above the line is a write or the beginning of one.
     */
    private static final Set<String> WRITE_SURFACE = Set.of(
        "no.sikt.graphitron.model.Tables",
        "FactSink",
        "no.sikt.graphitron.model.boot");

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern PERMITS = Pattern.compile("permits\\s+([^{]+)\\{");

    /** Whole packages that move, minus the files {@link #STAYS} excludes. */
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
        "DirectiveArgs.java",
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
        "model/ConnectionNaming.java",
        // The Rejection sealed closure. Rejection.AuthorError permits these ten top-level
        // interfaces, so they land in one package together or the move does not compile.
        "model/Rejection.java",
        "model/DeleteRowsError.java",
        "model/ErrorChannelWalkerError.java",
        "model/JooqRecordInputError.java",
        "model/MutationTableArgError.java",
        "model/PivotError.java",
        "model/ReflectionError.java",
        "model/ServiceCarrierShapeError.java",
        "model/ServiceMethodCallError.java",
        "model/UpdateRowsError.java",
        "model/WireCoercionError.java",
        // What those arms carry.
        "model/ConflictSite.java",
        "model/Arity.java",
        "model/MatchedKey.java");

    @Test
    void nothingInTheFactTierNamesJavapoet() throws IOException {
        var violations = new ArrayList<String>();
        for (Path file : moveSet()) {
            if (Files.readString(file).contains("no.sikt.graphitron.javapoet")) {
                violations.add(rel(file));
            }
        }
        assertThat(violations)
            .as("fact-tier files naming the emit library; carry the captured name instead and lift"
                + " it through CatalogRefs at the site that emits")
            .isEmpty();
    }

    @Test
    void nothingInTheFactTierImportsAboveTheLine() throws IOException {
        var violations = new ArrayList<String>();
        Set<String> inSet = qualifiedNames();
        for (Path file : moveSet()) {
            for (String line : Files.readAllLines(file)) {
                Matcher m = IMPORT.matcher(line);
                if (!m.find()) {
                    continue;
                }
                String imported = m.group(1);
                if (!imported.startsWith("no.sikt.graphitron.")
                        || imported.startsWith("no.sikt.graphitron.model.")
                        || resolvesInto(imported, inSet)) {
                    continue;
                }
                violations.add(rel(file) + "  imports  " + imported);
            }
        }
        assertThat(violations)
            .as("the fact tier importing the generator; either the imported file belongs in the"
                + " move set, or the fact tier should carry what it needs as a captured value")
            .isEmpty();
    }

    @Test
    void noSealedHierarchyStraddlesTheLine() throws IOException {
        var violations = new ArrayList<String>();
        List<Path> moving = moveSet();
        Set<String> movingPaths = new LinkedHashSet<>();
        for (Path file : moving) {
            movingPaths.add(rel(file));
        }
        for (Path file : moving) {
            String body = stripComments(Files.readString(file));
            String pkgDir = rel(file).contains("/")
                ? rel(file).substring(0, rel(file).lastIndexOf('/') + 1)
                : "";
            Matcher m = PERMITS.matcher(body);
            while (m.find()) {
                for (String name : m.group(1).split(",")) {
                    String top = name.trim().split("\\.")[0].split("<")[0].trim();
                    if (top.isEmpty()) {
                        continue;
                    }
                    String sibling = pkgDir + top + ".java";
                    if (Files.exists(REWRITE.resolve(sibling)) && !movingPaths.contains(sibling)) {
                        violations.add(rel(file) + "  permits  " + sibling);
                    }
                }
            }
        }
        assertThat(violations)
            .as("a sealed type in the move set whose permitted subclass stays above the line;"
                + " permitted subclasses of an unnamed-module sealed type must share its package,"
                + " so the move would not compile")
            .isEmpty();
    }

    @Test
    void nothingThatStaysWritesFacts() throws IOException {
        var violations = new ArrayList<String>();
        for (Path file : staysAboveTheLine()) {
            String body = Files.readString(file);
            for (String surface : WRITE_SURFACE) {
                if (body.contains(surface)) {
                    violations.add(rel(file) + "  names  " + surface);
                }
            }
        }
        assertThat(violations)
            .as("the generator writing facts; add the query to the fact tier and read what it"
                + " returns, and if a new fact is wanted, capture is where it is written")
            .isEmpty();
    }

    /**
     * The files that stay above the line: everything under the generator's own tree that the move
     * set does not claim. The complement of {@link #moveSet()}, and the population the write rule
     * is asked of.
     */
    private static List<Path> staysAboveTheLine() throws IOException {
        List<Path> staying;
        try (var paths = Files.walk(REWRITE)) {
            staying = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !moves(p))
                .sorted()
                .toList();
        }
        assertThat(staying)
            .as("the leftovers must be found on disk; an empty walk would pass the check vacuously")
            .hasSizeGreaterThanOrEqualTo(200);
        return staying;
    }

    private static List<Path> moveSet() throws IOException {
        List<Path> moving;
        try (var paths = Files.walk(REWRITE)) {
            moving = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(FactTierBoundaryTest::moves)
                .sorted()
                .toList();
        }
        assertThat(moving)
            .as("the move set must be found on disk; an empty walk would pass every check vacuously")
            .hasSizeGreaterThanOrEqualTo(100);
        return moving;
    }

    /** Every move-set file as a fully-qualified type name, for resolving an import against. */
    private static Set<String> qualifiedNames() throws IOException {
        var names = new LinkedHashSet<String>();
        for (Path file : moveSet()) {
            names.add("no.sikt.graphitron.rewrite."
                + rel(file).replace(".java", "").replace('/', '.'));
        }
        return names;
    }

    /**
     * Whether an import names a move-set type or a member of one. A static import or a nested type
     * carries trailing segments the file list does not, so segments are dropped until one matches.
     */
    private static boolean resolvesInto(String imported, Set<String> inSet) {
        String candidate = imported;
        while (candidate.contains(".")) {
            if (inSet.contains(candidate)) {
                return true;
            }
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
        }
        return false;
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static String rel(Path file) {
        return REWRITE.relativize(file).toString().replace('\\', '/');
    }

    private static boolean moves(Path file) {
        String rel = rel(file);
        if (STAYS.contains(rel)) {
            return false;
        }
        return MOVING_FILES.contains(rel)
            || MOVING_PACKAGES.contains(rel.substring(0, Math.max(rel.indexOf('/'), 0)));
    }
}
