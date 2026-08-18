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
     * generated query surface; test scope is the capture fixture, which drives the real pipeline
     * against a jOOQ catalog because rows a test asserts on have to come from a real run.
     */
    private static final Map<String, String> ALLOWED_REACTOR_DEPENDENCIES = Map.of(
        "graphitron-model", "compile",
        "graphitron", "test",
        "graphitron-sakila-db", "test");

    /** The language server's package: off limits in both trees, the module having no edge to it. */
    private static final String LSP_PACKAGE = "no.sikt.graphitron.lsp.";

    /** The generator's package: off limits in main sources, where the module has no edge to it. */
    private static final String GENERATOR_PACKAGE = "no.sikt.graphitron.rewrite.";

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
     * The three classification taxonomies, whose permits the module used to switch over. Main
     * sources are already covered by the generator-package scan, these being generator types on a
     * dependency main sources no longer have; tests keep {@code graphitron}, so tests are where a
     * permit switch can still be written.
     */
    private static final List<String> CLASSIFICATION_TAXONOMIES = List.of(
        "FieldClassification", "TypeClassification", "TypeBackingShape");

    /**
     * The classification walk's own relations. Ordinary tables on {@code graphitron-model}, a
     * dependency this module keeps, so nothing but a guard stops a reader reaching for them. The
     * family drains on the walk's own clock and a consumer of it does not.
     */
    private static final List<String> WALK_RELATIONS = List.of(
        "WALK_TYPE_BACKING_CLASS", "WALK_CLAIM_DOMAIN_TYPE", "WALK_CLAIM_DOMAIN_FIELD");

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
    void noGeneratorReferenceInMainSources() throws IOException {
        var findings = scan(mainSources(), List.of(GENERATOR_PACKAGE), path -> false);

        assertThat(findings)
            .as("no tool may name a generator type: an answer assembled from a projection handed in "
                + "cannot be extended without touching the pipeline, which is the cost reading the "
                + "store buys out. The generator is a test-scope fixture dependency and nothing more.")
            .isEmpty();
    }

    @Test
    void noClassificationPermitSwitchInTestSources() throws IOException {
        var findings = scan(testSources(), CLASSIFICATION_TAXONOMIES, path -> isSelf(path));

        assertThat(findings)
            .as("the classification permits are the generator's vocabulary about itself, and around "
                + "ninety exhaustive arms of it answered questions the store answers directly. The "
                + "fixture drives the pipeline to produce a capture; it does not read the taxonomy "
                + "back out, or the taxonomy returns through the tests.")
            .isEmpty();
    }

    @Test
    void noWalkRelationIsRead() throws IOException {
        var findings = new ArrayList<String>();
        findings.addAll(scan(mainSources(), WALK_RELATIONS, path -> false));
        findings.addAll(scan(testSources(), WALK_RELATIONS, path -> isSelf(path)));

        assertThat(findings)
            .as("the walk_ family is the classification walk's own scaffolding and it drains on that "
                + "walk's clock; a consumer of it does not. Where a walk relation looks like the "
                + "answer, the intent_ relation keyed the same way is the one to read.")
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
            String scope = element(block, "scope");
            declared.put(artifact, scope == null ? "compile" : scope);
        }

        assertThat(declared)
            .as("graphitron-mcp answers from the store and from what its host hands it, so the "
                + "store's schema is the whole of what it compiles against. A new reactor edge is "
                + "argued for here rather than noticed later; the test-scope pair is the capture "
                + "fixture, a named affordance rather than a blanket exemption for test scope.")
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
