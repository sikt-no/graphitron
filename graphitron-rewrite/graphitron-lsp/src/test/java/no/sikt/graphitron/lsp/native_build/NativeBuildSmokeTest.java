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
 * Verifies the Phase 6 native plumbing end-to-end on whichever host CI is
 * running on: the per-platform Maven profile produced a shared library under
 * {@code target/classes/lib/<os>-<arch>/}, the {@code BundledLibraryLookup} SPI
 * extracts it at runtime, and jtreesitter loads {@code tree_sitter_graphql}
 * against it and parses a trivial GraphQL document.
 *
 * <p>Coverage for Linux x86_64 / macOS x86_64 / macOS arm64 lives in three
 * separate {@code @EnabledOnOs} test methods so a single CI matrix run
 * exercises exactly one of them per host. Windows is a follow-up to R18 Phase
 * 6; the SPI throws a pointed {@code UnsupportedOperationException} on
 * unsupported hosts, which the existing platform-gated tests do not exercise.
 */
class NativeBuildSmokeTest {

    @Test
    @EnabledOnOs(value = OS.LINUX, architectures = "amd64")
    void linuxX86_64SharedLibraryLoadsAndParses() {
        runSmokeTest();
    }

    @Test
    @EnabledOnOs(value = OS.MAC, architectures = "x86_64")
    void macosX86_64SharedLibraryLoadsAndParses() {
        runSmokeTest();
    }

    @Test
    @EnabledOnOs(value = OS.MAC, architectures = "aarch64")
    void macosAarch64SharedLibraryLoadsAndParses() {
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
