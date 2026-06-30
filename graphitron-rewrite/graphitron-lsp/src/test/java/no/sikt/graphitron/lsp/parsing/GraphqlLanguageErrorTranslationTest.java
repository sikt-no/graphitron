package no.sikt.graphitron.lsp.parsing;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the surfaces graphitron-lsp owns around jtreesitter's load failures now
 * that both native pieces are bundled: the {@link GraphqlLanguage#looksLikeRuntimeFailure}
 * classifier and the single bundled-load-failure message
 * ({@link GraphqlLanguage#bundledLoadFailureMessage}). The end-to-end load path
 * (with no system libtree-sitter present) is covered by
 * {@link no.sikt.graphitron.lsp.native_build.NativeLibraryBundleTest}; this test
 * pins the wrapper text and classifier heuristics directly so the
 * diagnostic + docs-link contract can be reasoned about without a native
 * dependency on the test runner.
 */
class GraphqlLanguageErrorTranslationTest {

    @Test
    void looksLikeRuntimeFailure_recognisesUnsatisfiedLinkError() {
        UnsatisfiedLinkError e = new UnsatisfiedLinkError("any payload");
        assertThat(GraphqlLanguage.looksLikeRuntimeFailure(e)).isTrue();
    }

    @Test
    void looksLikeRuntimeFailure_recognisesSymbolFailure() {
        // jtreesitter's diagnostic when a bundled binary is present but cannot resolve a symbol.
        UnsatisfiedLinkError e = new UnsatisfiedLinkError("Unresolved symbol: tree_sitter_graphql");
        assertThat(GraphqlLanguage.looksLikeRuntimeFailure(e)).isTrue();
    }

    @Test
    void looksLikeRuntimeFailure_walksCauseChain() {
        // jtreesitter wraps the loader error in a RuntimeException at static init.
        Throwable wrapped = new RuntimeException(
            "failed to initialize",
            new RuntimeException("inner",
                new UnsatisfiedLinkError("Cannot open library: libtree-sitter.so")));
        assertThat(GraphqlLanguage.looksLikeRuntimeFailure(wrapped)).isTrue();
    }

    @Test
    void looksLikeRuntimeFailure_recognisesMessageOnlyDlopenFailure() {
        // Some loader implementations throw RuntimeException with a message instead of UnsatisfiedLinkError.
        RuntimeException e = new RuntimeException("cannot open shared object file libtree-sitter.so");
        assertThat(GraphqlLanguage.looksLikeRuntimeFailure(e)).isTrue();
    }

    @Test
    void looksLikeRuntimeFailure_ignoresUnrelatedError() {
        // A bug elsewhere in graphitron-lsp must not be silently translated to a runtime-load failure.
        RuntimeException e = new RuntimeException("NullPointerException in workspace indexer");
        assertThat(GraphqlLanguage.looksLikeRuntimeFailure(e)).isFalse();
    }

    @Test
    void bundledLoadFailureMessage_namesExtractedPathAndDocsLink() {
        // Simulate a load failure of an extracted-but-unloadable bundled runtime
        // (the noexec-tmpdir / corrupt-extract / missing-CRT case the spec calls out).
        Path bogus = Path.of("/tmp/graphitron-lsp-12345-libtree-sitter.so");
        String msg = GraphqlLanguage.bundledLoadFailureMessage(bogus);
        assertThat(msg)
            .contains(bogus.toString())
            .contains("bundled tree-sitter runtime")
            .contains("graphitron-tree-sitter-natives")
            .contains("noexec")
            .contains("graphitron.sikt.no")
            .contains("lsp-requirements");
        // The bundled model means we never tell the operator to install anything.
        assertThat(msg)
            .doesNotContain("brew install")
            .doesNotContain("vcpkg install")
            .doesNotContain("apt");
    }

    @Test
    void bundledLoadFailureMessage_handlesMissingPath() {
        // Extraction may fail before producing a file; the message must still be coherent.
        String msg = GraphqlLanguage.bundledLoadFailureMessage(null);
        assertThat(msg)
            .contains("(not extracted)")
            .contains("bundled tree-sitter runtime")
            .contains("lsp-requirements");
    }
}
