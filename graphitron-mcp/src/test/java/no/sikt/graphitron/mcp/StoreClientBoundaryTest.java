package no.sikt.graphitron.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard over what this module is: a client of the fact store. Every tool answers from
 * the {@link no.sikt.graphitron.model.read.StoreHandle} and the
 * {@link no.sikt.graphitron.model.boot.StoreReader} its host hands in, so the store's schema and
 * jOOQ are the whole of what it compiles against, and nothing here opens a connection or reads a
 * generator projection. Each test below is one of those properties restated as an assertion.
 *
 * <p>The two dependency halves are both needed and neither implies the other. The import scan
 * alone would pass a pom that still declares {@code graphitron} at compile scope, which is the
 * state that lets the next reader reach for a projection without noticing they are widening a
 * dependency. The pom assertion alone would pass a module that reaches the generator transitively,
 * which is how the language-server edge arrived in the first place.
 *
 * <p>The allowlist shape is deliberate. A denylist naming the artifacts this module used to carry
 * would assert the history rather than the rule, and would pass every reactor edge nobody has added
 * yet. Stating the permitted set means a new edge has to be argued for here rather than noticed
 * later.
 *
 * <p>This file is the one scan exclusion, and it is excluded because it holds the needles: the
 * forbidden package prefixes and type names appear here as the strings the scans search for. The
 * pom guard and the main-source scans still cover everything this file could otherwise hide.
 */
class StoreClientBoundaryTest {

    /**
     * The reactor edges this module may declare, by artifact and scope. Compile scope is the store's
     * generated query surface; test scope is the generated jOOQ catalog a fixture captures against
     * and the shared store harness it captures with.
     *
     * <p>The generator is on neither list, at neither scope, and that is the point of the map being
     * exhaustive: what used to keep the edge alive was a handful of tests that needed a real build,
     * and a test whose subject is the build and this module agreeing lives above both of them.
     *
     * <p>A test-jar edge is keyed separately from the jar of the same artifact, because the two are
     * different claims: one says what this module compiles against, the other says it takes its test
     * fixtures from the shared harnesses rather than building its own. Keying on the artifact alone
     * would let the second silently overwrite the first and pass.
     */
    private static final Map<String, String> ALLOWED_REACTOR_DEPENDENCIES = Map.of(
        "graphitron-model", "compile",
        "graphitron-sakila-db", "test",
        "graphitron-model test-jar", "test");

    /** The language server's package: off limits in both trees, the module having no edge to it. */
    private static final String LSP_PACKAGE = "no.sikt.graphitron.lsp.";

    /** The generator's package: off limits in both trees, the module having no edge to it. */
    private static final String GENERATOR_PACKAGE = "no.sikt.graphitron.rewrite.";

    /**
     * The generated packages that sit under the generator's package name without being the
     * generator: {@code graphitron-sakila-db} emits the fixture jOOQ models and the fixture services
     * and conditions there. A capture fixture names them, so the generator scan discounts them
     * before it looks, rather than the whole scan stopping at main sources on their account.
     */
    private static final List<String> GENERATED_FIXTURE_PACKAGES = List.of(
        "no.sikt.graphitron.rewrite.test.jooq",
        "no.sikt.graphitron.rewrite.test.services",
        "no.sikt.graphitron.rewrite.test.conditions",
        "no.sikt.graphitron.rewrite.multischemafixture");

    /**
     * The store's boot class and the JDBC vocabulary a hand-rolled connection needs. The module is
     * handed its handle and its reader, so naming any of these in main sources means something here
     * decided to open a connection for itself.
     */
    private static final List<String> CONNECTION_OWNERSHIP_NEEDLES = List.of(
        "GraphitronModelStore", "graphitron.store.directory",
        "java.sql.Connection", "java.sql.Driver", "DriverManager");

    /**
     * The one named exclusion from the connection guard: its connections are to the consumer's own
     * dev database, through the consumer's own driver, which is the {@code execute} tool's whole
     * subject and is not the fact store at all.
     */
    private static final String CONNECTION_OWNERSHIP_EXCLUSION = "DevQueryExecutor.java";

    /**
     * The three classification taxonomies, whose permits the module used to switch over. The
     * generator-package scan above already reaches them by their package, so this is the guard
     * against a same-named type arriving some other way: a copy under this module's own package, or
     * a re-export from somewhere the package scan does not name.
     */
    private static final List<String> CLASSIFICATION_TAXONOMIES = List.of(
        "FieldClassification", "TypeClassification", "TypeBackingShape");

    /** Floors on the scanned-file counts: a walk that reached nothing would otherwise pass. */
    private static final int MIN_MAIN_FILES = 20;
    private static final int MIN_TEST_FILES = 20;

    @Test
    void mainSourcesOpenNoConnectionOfTheirOwn() throws IOException {
        var findings = scan(mainSources(), CONNECTION_OWNERSHIP_NEEDLES,
            path -> path.getFileName().toString().equals(CONNECTION_OWNERSHIP_EXCLUSION));

        assertThat(findings)
            .as("graphitron-mcp is handed its store handle and its reader by the dev session, which "
                + "opens the store once and closes it at cleanup; a server that opened the persisted "
                + "file itself could silently read a different store than the one the session writes. "
                + "%s is the one exclusion, its connections being to the consumer's own database.",
                CONNECTION_OWNERSHIP_EXCLUSION)
            .isEmpty();
    }

    @Test
    void noLanguageServerReferenceInEitherTree() throws IOException {
        var findings = new ArrayList<String>();
        findings.addAll(scan(mainSources(), List.of(LSP_PACKAGE), path -> false));
        findings.addAll(scan(testSources(), List.of(LSP_PACKAGE), path -> isSelf(path)));

        assertThat(findings)
            .as("graphitron-mcp declares no edge to graphitron-lsp in any scope: the two surfaces "
                + "share the store the dev session keeps current, not a Java vocabulary over it. A "
                + "rule shared between them graduates to a store view rather than to an import.")
            .isEmpty();
    }

    @Test
    void noGeneratorReferenceInEitherTree() throws IOException {
        var findings = new ArrayList<String>();
        findings.addAll(scanDiscountingFixturePackages(mainSources(), path -> false));
        findings.addAll(scanDiscountingFixturePackages(testSources(), StoreClientBoundaryTest::isSelf));

        assertThat(findings)
            .as("no tool may name a generator type: an answer assembled from a projection handed in "
                + "cannot be extended without touching the pipeline, which is the cost reading the "
                + "store buys out. Tests are covered too, and that is the half this module used to "
                + "be missing: a test-scope edge is how the generator was reachable here at all, and "
                + "the tests that wanted one were about two tiers agreeing, so they live above both.")
            .isEmpty();
    }

    @Test
    void noClassificationPermitSwitchInTestSources() throws IOException {
        var findings = scan(testSources(), CLASSIFICATION_TAXONOMIES, path -> isSelf(path));

        assertThat(findings)
            .as("the classification permits are the generator's vocabulary about itself, and around "
                + "ninety exhaustive arms of it answered questions the store answers directly. A "
                + "fixture captures a real schema; it does not read the taxonomy back out, or the "
                + "taxonomy returns through the tests under a name of its own.")
            .isEmpty();
    }

    @Test
    void theReactorDependencySetIsExactlyTheAllowlist() throws IOException {
        String pom = Files.readString(moduleRoot().resolve("pom.xml"));
        var declared = new LinkedHashMap<String, String>();
        Matcher blocks = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
        while (blocks.find()) {
            String block = blocks.group(1);
            if (!"no.sikt".equals(element(block, "groupId"))) continue;
            String artifact = element(block, "artifactId");
            String type = element(block, "type");
            String scope = element(block, "scope");
            declared.put(type == null ? artifact : artifact + " " + type,
                scope == null ? "compile" : scope);
        }

        assertThat(declared)
            .as("graphitron-mcp answers from the store and from what its host hands it, so the "
                + "store's schema is the whole of what it compiles against. A new reactor edge is "
                + "argued for here rather than noticed later; the test-scope edges are the capture "
                + "fixture and the shared test harnesses, each a named affordance rather than a "
                + "blanket exemption for test scope.")
            .containsExactlyInAnyOrderEntriesOf(ALLOWED_REACTOR_DEPENDENCIES);
    }

    // ---- scanning ----

    /**
     * Every occurrence of a needle in a tree, as {@code <file>: <needle>} findings. Occurrences
     * rather than imports: a fully-qualified reference and an import are the same coupling, and the
     * needles here name nothing a main source has any other reason to mention.
     */
    private static List<String> scan(Path root, List<String> needles,
        java.util.function.Predicate<Path> excluded) throws IOException {
        var findings = new ArrayList<String>();
        int scanned = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                if (excluded.test(path)) continue;
                String source = Files.readString(path);
                for (String needle : needles) {
                    if (source.contains(needle)) {
                        findings.add(root.relativize(path) + ": " + needle);
                    }
                }
            }
        }
        assertThat(scanned)
            .as("the walk under %s reached too few files to be scanning the module at all", root)
            .isGreaterThanOrEqualTo(root.endsWith("main/java") ? MIN_MAIN_FILES : MIN_TEST_FILES);
        return findings;
    }

    /**
     * The generator-package scan, with the generated fixture packages taken out of each file first.
     * Blanking them rather than skipping the files that mention them: a fixture naming a generated
     * jOOQ table and also naming a planner is exactly the finding, and skipping the file would hide
     * it.
     */
    private static List<String> scanDiscountingFixturePackages(
        Path root, java.util.function.Predicate<Path> excluded
    ) throws IOException {
        var findings = new ArrayList<String>();
        int scanned = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                if (excluded.test(path)) continue;
                String source = Files.readString(path);
                for (String generated : GENERATED_FIXTURE_PACKAGES) {
                    source = source.replace(generated, "");
                }
                if (source.contains(GENERATOR_PACKAGE)) {
                    findings.add(root.relativize(path) + ": " + GENERATOR_PACKAGE);
                }
            }
        }
        assertThat(scanned)
            .as("the walk under %s reached too few files to be scanning the module at all", root)
            .isGreaterThanOrEqualTo(root.endsWith("main/java") ? MIN_MAIN_FILES : MIN_TEST_FILES);
        return findings;
    }

    private static boolean isSelf(Path path) {
        return path.getFileName().toString()
            .equals(StoreClientBoundaryTest.class.getSimpleName() + ".java");
    }

    private static String element(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">").matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private static Path mainSources() {
        return moduleRoot().resolve("src/main/java");
    }

    private static Path testSources() {
        return moduleRoot().resolve("src/test/java");
    }

    /**
     * This module's own directory, found by walking up to the repository root from wherever the test
     * runs. Surefire runs from the module directory, but an IDE may not, and a scan rooted at the
     * wrong place would pass by reaching nothing; the file-count floors are the second half of that
     * defence.
     */
    private static Path moduleRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("roadmap/workflow.adoc"))) {
                return p.resolve("graphitron-mcp");
            }
        }
        throw new IllegalStateException("Could not locate the repository root by walking up from " + cwd);
    }
}
