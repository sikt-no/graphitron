package no.sikt.graphitron.lsp;

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
 * Structural guard over what this module is: a client of the fact store. The language server parses
 * the buffer under the cursor with tree-sitter and reads everything else out of the
 * {@link no.sikt.graphitron.model.read.StoreHandle} its host hands in, so the store's schema and
 * jOOQ are the whole of what it compiles against, and nothing here names a generator type. Each
 * test below is one of those properties restated as an assertion.
 *
 * <p>The sibling of {@code graphitron-mcp}'s guard of the same name, and deliberately its shape:
 * the two clients are the same kind of thing, they lost the same edge for the same reason, and a
 * rule stated once for one of them would leave the other free to reacquire it. What differs is the
 * allowlist and which sibling package is off limits, because those are the facts about each module.
 *
 * <p>The two dependency halves are both needed and neither implies the other. The source scan alone
 * would pass a pom that still declares {@code graphitron}, which is the state that lets the next
 * reader reach for a projection without noticing they are widening a dependency. The pom assertion
 * alone would pass a module that reaches the generator transitively.
 *
 * <p>This file is the one scan exclusion, and it is excluded because it holds the needles: the
 * forbidden package prefixes appear here as the strings the scans search for. The pom guard and the
 * main-source scans still cover everything this file could otherwise hide.
 */
class StoreClientBoundaryTest {

    /**
     * The reactor edges this module may declare, by artifact and scope. Compile scope is the store's
     * generated query surface and the tree-sitter natives the parser loads; test scope is the
     * generated jOOQ catalog a fixture captures against and the shared store harness it captures
     * with.
     *
     * <p>The generator is on neither list, at neither scope. What used to keep the edge alive was a
     * handful of tests that needed a real build, and a test whose subject is the build and the
     * editor agreeing lives above both of them.
     *
     * <p>A test-jar edge is keyed separately from the jar of the same artifact, because the two are
     * different claims: one says what this module compiles against, the other says it takes its test
     * fixtures from the shared harness rather than building its own.
     */
    private static final Map<String, String> ALLOWED_REACTOR_DEPENDENCIES = Map.of(
        "graphitron-model", "compile",
        "graphitron-tree-sitter-natives", "compile",
        "graphitron-sakila-db", "test",
        "graphitron-model test-jar", "test");

    /** The MCP server's package: off limits in both trees, the module having no edge to it. */
    private static final String MCP_PACKAGE = "no.sikt.graphitron.mcp.";

    /** The generator's package: off limits in both trees, the module having no edge to it. */
    private static final String GENERATOR_PACKAGE = "no.sikt.graphitron.rewrite.";

    /**
     * The generated packages that sit under the generator's package name without being the
     * generator: {@code graphitron-sakila-db} emits the fixture jOOQ models and the fixture services
     * and conditions there. A capture fixture names them, so the generator scan discounts them
     * before it looks, rather than stopping at main sources on their account.
     */
    private static final List<String> GENERATED_FIXTURE_PACKAGES = List.of(
        "no.sikt.graphitron.rewrite.test.jooq",
        "no.sikt.graphitron.rewrite.test.services",
        "no.sikt.graphitron.rewrite.test.conditions",
        "no.sikt.graphitron.rewrite.multischemafixture");

    /** Floors on the scanned-file counts: a walk that reached nothing would otherwise pass. */
    private static final int MIN_MAIN_FILES = 20;
    private static final int MIN_TEST_FILES = 20;

    @Test
    void noGeneratorReferenceInEitherTree() throws IOException {
        var findings = new ArrayList<String>();
        findings.addAll(scanForGenerator(mainSources(), path -> false));
        findings.addAll(scanForGenerator(testSources(), StoreClientBoundaryTest::isSelf));

        assertThat(findings)
            .as("no provider may name a generator type: an answer assembled from a projection handed "
                + "in cannot be extended without touching the pipeline, which is the cost reading "
                + "the store buys out. Tests are covered too, and that is the half that mattered: a "
                + "test-scope edge is how the generator was reachable here at all, and the tests "
                + "that wanted one were about two tiers agreeing, so they live above both now.")
            .isEmpty();
    }

    @Test
    void noMcpReferenceInEitherTree() throws IOException {
        var findings = new ArrayList<String>();
        findings.addAll(scan(mainSources(), List.of(MCP_PACKAGE), path -> false));
        findings.addAll(scan(testSources(), List.of(MCP_PACKAGE), StoreClientBoundaryTest::isSelf));

        assertThat(findings)
            .as("graphitron-lsp declares no edge to graphitron-mcp in any scope: the two surfaces "
                + "share the store the dev session keeps current, not a Java vocabulary over it. A "
                + "rule shared between them graduates to a store view rather than to an import.")
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
            .as("graphitron-lsp answers from the store and from the buffer under the cursor, so the "
                + "store's schema is the whole of what it compiles against. A new reactor edge is "
                + "argued for here rather than noticed later; the test-scope edges are the generated "
                + "catalog and the shared harness, each a named affordance rather than a blanket "
                + "exemption for test scope.")
            .containsExactlyInAnyOrderEntriesOf(ALLOWED_REACTOR_DEPENDENCIES);
    }

    // ---- scanning ----

    /**
     * The generator-package scan, with the generated fixture packages taken out of each file first.
     * Blanking them rather than skipping the files that mention them: a fixture naming a generated
     * jOOQ table and also naming a planner is exactly the finding, and skipping the file would hide
     * it.
     */
    private static List<String> scanForGenerator(
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
        assertScanReached(scanned, root);
        return findings;
    }

    /**
     * Every occurrence of a needle in a tree, as {@code <file>: <needle>} findings. Occurrences
     * rather than imports: a fully-qualified reference and an import are the same coupling, and the
     * needles here name nothing a source has any other reason to mention.
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
        assertScanReached(scanned, root);
        return findings;
    }

    private static void assertScanReached(int scanned, Path root) {
        assertThat(scanned)
            .as("the walk under %s reached too few files to be scanning the module at all", root)
            .isGreaterThanOrEqualTo(root.endsWith("main/java") ? MIN_MAIN_FILES : MIN_TEST_FILES);
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
                return p.resolve("graphitron-lsp");
            }
        }
        throw new IllegalStateException("Could not locate the repository root by walking up from " + cwd);
    }
}
