package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Language;

import java.lang.foreign.Arena;
import java.nio.file.Path;

/**
 * Singleton holder for the {@code tree-sitter-graphql} language binding.
 *
 * <p>Both native pieces, the grammar and the {@code libtree-sitter} runtime,
 * ship in {@code no.sikt:graphitron-tree-sitter-natives} and are loaded through
 * {@link BundledLibraryLookup} (the registered jtreesitter SPI). There is no
 * system dependency: nothing for the operator to install. The only remaining
 * failure mode is the bundled runtime failing to load after extraction, which
 * happens when the temp directory is mounted {@code noexec}, the extracted file
 * is corrupt, or a required system C runtime is absent. This class translates
 * that {@link UnsatisfiedLinkError} (or {@link RuntimeException} wrapping one)
 * into a single startup-failure that names the extracted path, rather than
 * letting an opaque link error surface. An unsupported host architecture fails
 * earlier, at {@link BundledLibraryLookup}'s
 * {@link UnsupportedOperationException}.
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
            if (looksLikeRuntimeFailure(e)) {
                throw new IllegalStateException(bundledLoadFailureMessage(BundledLibraryLookup.extractedRuntimePath()), e);
            }
            throw e;
        }
    }

    static boolean looksLikeRuntimeFailure(Throwable e) {
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

    /**
     * The single bundled-runtime load-failure diagnostic. Package-private so the
     * unit test can pin the contract without touching the static {@link #INSTANCE}
     * init. {@code runtimePath} is the temp file the runtime was extracted to (or
     * {@code null} if extraction never produced one).
     */
    static String bundledLoadFailureMessage(Path runtimePath) {
        String where = runtimePath != null ? runtimePath.toString() : "(not extracted)";
        return "graphitron-lsp could not load the bundled tree-sitter runtime extracted to "
            + where + ". The grammar and the runtime both ship in "
            + "graphitron-tree-sitter-natives, so this is not a missing-dependency problem. "
            + "The usual causes are a temp directory mounted noexec, a corrupt extracted file, "
            + "or a missing system C runtime. Set java.io.tmpdir to an exec-capable directory, "
            + "or see " + DOCS_URL;
    }

    private static final String DOCS_URL =
        "https://graphitron.sikt.no/manual/reference/lsp-requirements.html";
}
