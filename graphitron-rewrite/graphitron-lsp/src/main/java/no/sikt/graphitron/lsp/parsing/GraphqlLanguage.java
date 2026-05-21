package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Language;

import java.lang.foreign.Arena;
import java.util.Locale;

/**
 * Singleton holder for the {@code tree-sitter-graphql} language binding.
 *
 * <p>The grammar binary ships via {@code no.sikt:graphitron-tree-sitter-natives}
 * and is loaded through {@link BundledLibraryLookup}. The tree-sitter runtime
 * itself ({@code libtree-sitter}) is sourced from the consumer's OS via
 * jtreesitter's library-loading chain; this class translates the resulting
 * {@link UnsatisfiedLinkError} (or {@link RuntimeException} wrapping one) into
 * a startup-failure that names the per-platform install command, so an
 * operator running the LSP on a fresh machine sees actionable guidance rather
 * than an opaque link error.
 *
 * <p>The {@link Arena#global() global arena} is intentional: the
 * {@link Language} keeps its native pointer alive for the JVM's lifetime,
 * matching how the LSP uses it (one parser pool, hot reload via the
 * {@code dev} mojo's filesystem watcher rather than process recycling).
 */
public final class GraphqlLanguage {

    private static final Language INSTANCE = loadOrExplain();

    private GraphqlLanguage() {}

    public static Language get() {
        return INSTANCE;
    }

    private static Language loadOrExplain() {
        try {
            return Language.load(
                new BundledLibraryLookup().get(Arena.global()),
                "tree_sitter_graphql"
            );
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            if (looksLikeMissingRuntime(e)) {
                throw new IllegalStateException(missingRuntimeMessage(), e);
            }
            throw e;
        }
    }

    private static boolean looksLikeMissingRuntime(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnsatisfiedLinkError) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("tree-sitter") || msg.contains("tree_sitter"))
                && (msg.contains("cannot open") || msg.contains("not found")
                    || msg.contains("library") || msg.contains("symbol"))) {
                return true;
            }
        }
        return false;
    }

    static String missingRuntimeMessage() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String install;
        if (os.contains("linux")) {
            install = "sudo apt install libtree-sitter0  (Debian/Ubuntu)  "
                + "or the equivalent libtree-sitter package for your distro";
        } else if (os.contains("mac")) {
            install = "brew install tree-sitter";
        } else if (os.contains("windows")) {
            install = "vcpkg install tree-sitter:x64-windows  "
                + "(or a pinned upstream build, placed on PATH)";
        } else {
            install = "install libtree-sitter for your platform and ensure it is on "
                + "the OS library search path";
        }
        return "graphitron-lsp could not find libtree-sitter on this system. "
            + "The grammar binary ships with graphitron-tree-sitter-natives, but the "
            + "tree-sitter runtime itself is a system dependency. To install it:\n  "
            + install;
    }
}

