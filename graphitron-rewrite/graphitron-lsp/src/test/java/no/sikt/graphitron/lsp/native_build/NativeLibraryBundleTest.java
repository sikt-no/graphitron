package no.sikt.graphitron.lsp.native_build;

import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the end-to-end native plumbing on whichever host CI is running on:
 * the {@code graphitron-tree-sitter-natives} jar carries a grammar binary
 * under {@code lib/<os>-<arch>/}, {@code BundledLibraryLookup} extracts it
 * at runtime, and jtreesitter loads {@code tree_sitter_graphql} against it
 * (with the system-installed {@code libtree-sitter} providing the runtime
 * symbols) and parses a trivial GraphQL document.
 *
 * <p>Coverage for each supported platform lives in a separate
 * {@code @EnabledOnOs} method so a CI matrix run exercises exactly one per
 * host. In regular per-PR CI only the {@code linux-x86_64} method executes;
 * the {@code linux-aarch64}, {@code macos-aarch64}, and {@code windows-x86_64}
 * methods run on their respective platforms in the natives release
 * workflow's post-deploy matrix.
 */
class NativeLibraryBundleTest {

    @Test
    @EnabledOnOs(value = OS.LINUX, architectures = "amd64")
    void linuxX86_64SharedLibraryLoadsAndParses() {
        runSmokeTest();
    }

    @Test
    @EnabledOnOs(value = OS.LINUX, architectures = "aarch64")
    void linuxAarch64SharedLibraryLoadsAndParses() {
        runSmokeTest();
    }

    @Test
    @EnabledOnOs(value = OS.MAC, architectures = "aarch64")
    void macosAarch64SharedLibraryLoadsAndParses() {
        runSmokeTest();
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
    void windowsX86_64SharedLibraryLoadsAndParses() {
        runSmokeTest();
    }

    private static void runSmokeTest() {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup symbols = new no.sikt.graphitron.lsp.parsing.BundledLibraryLookup().get(arena);
            Language language = Language.load(symbols, "tree_sitter_graphql");

            try (Parser parser = new Parser(language)) {
                var tree = parser.parse("type Query { hello: String }").orElseThrow();
                try (tree) {
                    var root = tree.getRootNode();
                    assertThat(root.getType()).isEqualTo("source_file");
                    assertThat(root.hasError()).isFalse();
                }
            }
        }
    }
}
