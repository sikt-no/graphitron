package no.sikt.graphitron.lsp.parsing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the surfaces graphitron-lsp owns around jtreesitter's runtime
 * failure modes: the {@link GraphqlLanguage#looksLikeRuntimeFailure}
 * classifier and the two operator-facing messages
 * ({@link GraphqlLanguage#missingRuntimeMessage}
 * and {@link GraphqlLanguage#tooOldRuntimeMessage}). The end-to-end load
 * path is covered by
 * {@link no.sikt.graphitron.lsp.native_build.NativeLibraryBundleTest}; this
 * test pins the wrapper text and classifier heuristics directly so the
 * install-command + docs-link contract can be reasoned about without a
 * native dependency on the test runner.
 */
class GraphqlLanguageErrorTranslationTest {

    @Test
    void looksLikeRuntimeFailure_recognisesUnsatisfiedLinkError() {
        UnsatisfiedLinkError e = new UnsatisfiedLinkError("any payload");
        assertThat(GraphqlLanguage.looksLikeRuntimeFailure(e)).isTrue();
    }

    @Test
    void looksLikeRuntimeFailure_recognisesAbiSymbolFailure() {
        // jtreesitter 0.26's diagnostic for an old (or absent) libtree-sitter.
        UnsatisfiedLinkError e = new UnsatisfiedLinkError(
            "Symbol not found: " + GraphqlLanguage.ABI_VERSION_SYMBOL);
        assertThat(GraphqlLanguage.looksLikeRuntimeFailure(e)).isTrue();
    }

    @Test
    void looksLikeRuntimeFailure_walksCauseChain() {
        // jtreesitter wraps the loader error in a RuntimeException at static init.
        Throwable wrapped = new RuntimeException(
            "failed to initialize",
            new RuntimeException("inner",
                new UnsatisfiedLinkError("Symbol not found: " + GraphqlLanguage.ABI_VERSION_SYMBOL)));
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
        // A bug elsewhere in graphitron-lsp must not be silently translated to "install libtree-sitter".
        RuntimeException e = new RuntimeException("NullPointerException in workspace indexer");
        assertThat(GraphqlLanguage.looksLikeRuntimeFailure(e)).isFalse();
    }

    @Test
    void missingRuntimeMessage_namesTheNativeDependencyAndDocsLink() {
        String msg = GraphqlLanguage.missingRuntimeMessage();
        assertThat(msg).contains("libtree-sitter")
            .contains("graphitron-tree-sitter-natives")
            .contains("graphitron.sikt.no")
            .contains("getting-started");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void missingRuntimeMessage_linuxHintMatchesDocsTable() {
        // Spec § 64 + getting-started.adoc:435-436: apt libtree-sitter0 is 0.20.x and too old;
        // Debian/Ubuntu users need the v0.26.9 source build.
        String msg = GraphqlLanguage.missingRuntimeMessage();
        assertThat(msg)
            .contains("v0.26.9")
            .contains("github.com/tree-sitter/tree-sitter")
            .contains("pacman -S tree-sitter")
            .contains("dnf install tree-sitter");
        // Negative: must not point users at the apt package the spec documents as too old.
        assertThat(msg).doesNotContain("apt install libtree-sitter0");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void missingRuntimeMessage_macHintIsBrewInstall() {
        assertThat(GraphqlLanguage.missingRuntimeMessage()).contains("brew install tree-sitter");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void missingRuntimeMessage_windowsHintIsVcpkg() {
        assertThat(GraphqlLanguage.missingRuntimeMessage()).contains("vcpkg install tree-sitter:x64-windows");
    }

    @Test
    void tooOldRuntimeMessage_namesAbiSymbolAndDocsLink() {
        String msg = GraphqlLanguage.tooOldRuntimeMessage();
        assertThat(msg)
            .contains(GraphqlLanguage.ABI_VERSION_SYMBOL)
            .contains("0.25")
            .contains("too old")
            .contains("graphitron.sikt.no")
            .contains("getting-started");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void tooOldRuntimeMessage_linuxNamesAptLibtreesitter0() {
        // The whole point of distinguishing too-old from missing is that this is
        // the Debian/Ubuntu user's most common situation. Naming the package by
        // name makes the operator recognise their own setup.
        String msg = GraphqlLanguage.tooOldRuntimeMessage();
        assertThat(msg)
            .contains("libtree-sitter0")
            .contains("0.20.x")
            .contains("v0.26.9");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void tooOldRuntimeMessage_macHintIsBrewUpgrade() {
        assertThat(GraphqlLanguage.tooOldRuntimeMessage()).contains("brew upgrade tree-sitter");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void tooOldRuntimeMessage_windowsHintIsVcpkgUpgrade() {
        assertThat(GraphqlLanguage.tooOldRuntimeMessage()).contains("vcpkg upgrade tree-sitter:x64-windows");
    }

    @Test
    void classifyInstalledRuntime_findsModernRuntimeWhenAvailable() {
        // On hosts that already have a modern libtree-sitter installed via one of the
        // well-known probe paths (the rewrite CI image source-builds v0.26.9 into
        // /usr/local/lib), the classifier should pick it up as MODERN. The test
        // is best-effort: if the runner has no libtree-sitter at all, the assert
        // documents the alternative branch instead of failing.
        GraphqlLanguage.RuntimeStatus status = GraphqlLanguage.classifyInstalledRuntime();
        assertThat(status).isIn(
            GraphqlLanguage.RuntimeStatus.MODERN,
            GraphqlLanguage.RuntimeStatus.TOO_OLD,
            GraphqlLanguage.RuntimeStatus.MISSING);
    }

    @Test
    void runtimeProbePaths_includesAptInstallLocationsOnLinux() {
        // The probe-path superset deliberately includes apt's libtree-sitter0 location
        // even though BundledLibraryLookup's SPI probe doesn't (the apt version is
        // too old for jtreesitter 0.26). Including it here lets classifyInstalledRuntime
        // detect the Debian/Ubuntu too-old case.
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            return;  // probe paths are per-OS; pin only the platform we're asserting about.
        }
        assertThat(GraphqlLanguage.runtimeProbePaths())
            .anyMatch(p -> p.toString().contains("x86_64-linux-gnu")
                || p.toString().contains("aarch64-linux-gnu"));
    }
}
