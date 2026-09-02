package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;

/**
 * Cross-cutting ratchet for the three-clause generator contract
 * (determinism + minimal-change writes + clean removal) against the
 * full rewrite-test fixture schema, which exercises every emitter
 * (interfaces, unions, directives, @splitQuery, @asConnection,
 * @lookupKey, input types, enums, federation). The shallow unit tests
 * in IdempotentWriterTest cover the writer mechanics on a two-type SDL;
 * this test is the real determinism guardrail.
 *
 * <p>Every case needs a populated output tree to start from, and only one of them needs that tree to
 * have been produced independently of the tree it is compared against. So the class builds one
 * canonical tree per JVM and each case copies it, which is what keeps the run count at what the
 * contract needs rather than at one per case: four runs, of which the canonical one is shared three
 * ways. A case copies rather than writes into the shared tree, so the shared tree stays pristine and
 * the cases stay order-independent.
 *
 * <p>Clean removal is held here and not only against the two-type SDL, where an orphan sweep has
 * almost nothing to sweep and no chance to sweep the wrong thing. Over the full fixture it has six
 * owned subpackages of real emitted units to get right, and an unowned one to leave alone.
 *
 * <p>Breadth found something the two-type SDL could not, and it is worth knowing while reading the
 * case: the generator emits into four further subpackages ({@code federated}, {@code multitenant},
 * {@code multischema}, {@code multischemamutation}) that its sweep does not visit, so a unit the
 * schema stops calling for is left behind in any of them. That is a defect in the sweep's owned
 * set rather than in this test, it is filed as its own change, and this case deliberately does not
 * assert it either way: pinning it as correct would make the fix look like a regression.
 */
@Tag("cross-cutting")
class GeneratorDeterminismTest {

    private static final Path FIXTURE_SCHEMA =
        Path.of("src/main/resources/graphql/schema.graphqls").toAbsolutePath();

    private static final String OUTPUT_PACKAGE = "no.sikt.graphitron.generated";
    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    /**
     * The subpackages {@link GraphQLRewriteGenerator}'s sweep owns, mirrored here because its own
     * list is private. The root package is owned too and is exercised by the whole-tree assertion
     * below rather than by a planted file, there being emitted units directly in it.
     */
    private static final List<String> OWNED =
        List.of("util", "schema", "types", "conditions", "fetchers", "inputs");

    /**
     * A subpackage nothing emits into, standing for a consumer's own hand-written code sharing the
     * output package. The sweep must leave it alone, which is the half of clean removal that a
     * too-eager implementation gets wrong.
     */
    private static final String UNOWNED = "handwritten";

    @TempDir
    static Path shared;

    /** The canonical populated tree, produced once and copied by every case below. */
    private static Path canonical;

    @BeforeAll
    static void generateCanonicalTree() throws IOException {
        canonical = Files.createDirectories(shared.resolve("canonical"));
        new GraphQLRewriteGenerator(contextFor(canonical)).generate();
        assertThat(readAll(canonical)).as("the canonical tree").isNotEmpty();
    }

    @Test
    void twoIndependentRunsProduceIdenticalOutputTrees(@TempDir Path root) throws IOException {
        Path other = Files.createDirectories(root.resolve("other"));
        new GraphQLRewriteGenerator(contextFor(other)).generate();

        Map<String, Path> canonicalFiles = index(canonical);
        Map<String, Path> otherFiles = index(other);

        assertThat(otherFiles.keySet())
            .as("the two runs should emit the same set of files")
            .isEqualTo(canonicalFiles.keySet());
        for (var entry : canonicalFiles.entrySet()) {
            long offset = Files.mismatch(entry.getValue(), otherFiles.get(entry.getKey()));
            assertThat(offset)
                .as("%s differs between two independent runs, first at byte %d",
                    entry.getKey(), offset)
                .isEqualTo(-1L);
        }
    }

    @Test
    void aRunAgainstAnAlreadyGeneratedTreePreservesMtimes(@TempDir Path root) throws IOException {
        Path outDir = copyOfCanonical(root.resolve("out"));

        // Wind all mtimes back 2 seconds so a rewrite would advance them to "now"
        // and be detectable by equality; the content-idempotent write skips the
        // disk write and leaves the backdated mtime intact.
        backdate(outDir);
        Map<Path, Long> before = mtimes(outDir);
        assertThat(before).isNotEmpty();

        new GraphQLRewriteGenerator(contextFor(outDir)).generate();

        assertThat(mtimes(outDir)).isEqualTo(before);
    }

    @Test
    void orphansAreSweptFromEveryOwnedSubpackageAndOnlyThose(@TempDir Path root) throws IOException {
        Path outDir = copyOfCanonical(root.resolve("out"));

        var orphans = new ArrayList<Path>();
        for (String subpackage : OWNED) {
            orphans.add(plant(outDir, subpackage, "StaleOrphan.java"));
        }
        Path foreign = plant(outDir, UNOWNED, "HandWritten.java");
        Map<String, Path> emitted = index(outDir);

        new GraphQLRewriteGenerator(contextFor(outDir)).generate();

        assertThat(orphans)
            .as("a unit the schema no longer calls for, in a subpackage the generator owns")
            .allSatisfy(orphan -> assertThat(orphan).doesNotExist());
        assertThat(foreign)
            .as("a file in a subpackage the generator does not own is not its to delete")
            .exists();
        assertThat(index(outDir).keySet())
            .as("the sweep should remove the orphans and nothing else")
            .isEqualTo(emitted.keySet().stream()
                .filter(name -> !name.endsWith("StaleOrphan.java"))
                .collect(Collectors.toSet()));
    }

    // ===== The shared tree =====

    private static Path copyOfCanonical(Path target) throws IOException {
        Files.createDirectories(target);
        Files.walkFileTree(canonical, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(canonical.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(canonical.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
        return target;
    }

    private static Path plant(Path outDir, String subpackage, String fileName) throws IOException {
        Path dir = outDir;
        for (String segment : (OUTPUT_PACKAGE + "." + subpackage).split("\\.")) {
            dir = dir.resolve(segment);
        }
        Files.createDirectories(dir);
        Path planted = dir.resolve(fileName);
        Files.writeString(planted, "// planted by the ratchet\n", UTF_8);
        return planted;
    }

    private static void backdate(Path root) throws IOException {
        long past = System.currentTimeMillis() - 2_000;
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try { Files.setLastModifiedTime(p, FileTime.fromMillis(past)); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    private static RunContext contextFor(Path outputDir) {
        // The fixture schema binds a $session service parameter, so the context needs the same
        // <sessionState> pair the example pom configures for the default execution.
        return new RunContext(
            List.of(new SchemaInput(SchemaSource.file(FIXTURE_SCHEMA), Optional.empty(), Optional.empty())),
            FIXTURE_SCHEMA.getParent(), "GeneratorDeterminismTest",
            outputDir,
            OUTPUT_PACKAGE,
            JOOQ_PACKAGE
        ).withSessionStateConfig(SessionStateConfig.from(
            "no.sikt.graphitron.rewrite.test.services.SakilaSessionIdentity#mount",
            "no.sikt.graphitron.rewrite.test.services.SakilaSessionIdentity#unmount"));
    }

    /**
     * Relative path to file, for every file under {@code root}. Paths rather than contents: when
     * this ratchet fires it is over one generated Java file among hundreds, and
     * {@link Files#mismatch} names the differing byte offset without either tree being read into
     * memory or an assertion library diffing two long strings.
     */
    private static Map<String, Path> index(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .collect(Collectors.toMap(p -> root.relativize(p).toString(), p -> p,
                    (a, b) -> a, TreeMap::new));
        }
    }

    private static Map<String, String> readAll(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .collect(Collectors.toMap(
                    p -> root.relativize(p).toString(),
                    p -> {
                        try { return Files.readString(p, UTF_8); }
                        catch (IOException e) { throw new RuntimeException(e); }
                    }
                ));
        }
    }

    private static Map<Path, Long> mtimes(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .collect(Collectors.toMap(p -> p, p -> {
                    try { return Files.getLastModifiedTime(p).toMillis(); }
                    catch (IOException e) { throw new RuntimeException(e); }
                }));
        }
    }
}
