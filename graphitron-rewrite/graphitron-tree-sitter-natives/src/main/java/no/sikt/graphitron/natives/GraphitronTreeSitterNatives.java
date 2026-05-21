package no.sikt.graphitron.natives;

/**
 * Marker class for the {@code graphitron-tree-sitter-natives} jar.
 *
 * <p>The jar ships per-platform tree-sitter shared libraries under
 * {@code lib/<os>-<arch>/} on the classpath and has no callable Java
 * surface of its own. The library-loading logic lives in
 * graphitron-lsp's {@code BundledLibraryLookup}; this artifact is a
 * pure resource carrier.
 *
 * <p>The class itself exists only so {@code maven-javadoc-plugin}'s
 * {@code jar} goal has at least one public type to document. Without
 * it the plugin aborts with "No public or protected classes found to
 * document," and Maven Central rejects the release for missing a
 * javadoc jar.
 */
public final class GraphitronTreeSitterNatives {
    private GraphitronTreeSitterNatives() {}
}
