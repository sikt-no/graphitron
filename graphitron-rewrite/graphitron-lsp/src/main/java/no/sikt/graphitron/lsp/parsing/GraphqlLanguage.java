package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Language;

import java.lang.foreign.Arena;

/**
 * Singleton holder for the {@code tree-sitter-graphql} language binding.
 *
 * <p>Loads via {@code jtreesitter}, which itself resolves runtime symbols
 * through the {@link io.github.treesitter.jtreesitter.NativeLibraryLookup}
 * SPI registered under {@code META-INF/services}; that SPI extracts the
 * platform-appropriate shared library shipped under {@code lib/<os>-<arch>/}
 * inside this jar (Linux x86_64 today; macOS / Windows in R18 Phase 6
 * step 4). The combined library exports both jtreesitter's runtime symbols
 * and the grammar's {@code tree_sitter_graphql} entry point, so a single
 * {@link Language#load} call covers both.
 *
 * <p>The {@link Arena#global() global arena} is intentional: the
 * {@link Language} keeps its native pointer alive for the JVM's lifetime,
 * matching how the LSP uses it (one parser pool, hot reload via the
 * {@code dev} mojo's filesystem watcher rather than process recycling).
 */
public final class GraphqlLanguage {

    private static final Language INSTANCE = Language.load(
        new BundledLibraryLookup().get(Arena.global()),
        "tree_sitter_graphql"
    );

    private GraphqlLanguage() {}

    public static Language get() {
        return INSTANCE;
    }
}
