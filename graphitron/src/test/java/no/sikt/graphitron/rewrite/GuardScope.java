package no.sikt.graphitron.rewrite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared walk scope for the prose guards ({@link RoadmapReferenceGuardTest} and
 * {@link RetiredVocabularyGuardTest}): the in-scope module roots and the repository-root
 * anchor both guards walk from. One definition, so a new module cannot silently join one
 * guard's scope and not the other's.
 */
final class GuardScope {

    /**
     * Module source roots to scan, relative to the repository root. {@code roadmap-tool} is
     * excluded by design: its entire domain is roadmap items, so an item id in its sources is
     * a legitimate reference rather than a stale citation.
     */
    static final List<String> IN_SCOPE_MODULES = List.of(
        "graphitron",
        "graphitron-javapoet",
        "graphitron-jakarta-rest",
        "graphitron-mcp",
        "graphitron-lsp",
        "graphitron-maven-plugin",
        "graphitron-fixtures-codegen",
        "graphitron-sakila-db",
        "graphitron-sakila-service",
        "graphitron-sakila-example"
    );

    private GuardScope() {}

    /**
     * Walks up from the test working directory to the repository root, identified by the
     * {@code roadmap/workflow.adoc} anchor. Surefire runs from the module directory, so the
     * root is one or more parents up.
     */
    static Path locateRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("roadmap/workflow.adoc"))) return p;
        }
        throw new IllegalStateException("Could not locate the repository root by walking up from " + cwd);
    }
}
