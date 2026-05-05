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
 * Verifies the Phase 6 step 2 plumbing end-to-end: the Maven build profile
 * produces a per-platform shared library under {@code target/classes/lib/...}
 * which the {@code BundledLibraryLookup} SPI extracts at runtime, and
 * jtreesitter loads {@code tree_sitter_graphql} against it and parses a
 * trivial GraphQL document.
 *
 * <p>Linux-x86_64 only today; step 4 adds the matching {@code @EnabledOnOs}
 * cases for macOS x86_64 / macOS arm64 / Windows x86_64.
 *
 * <p>The {@link io.github.treesitter.jtreesitter.NativeLibraryLookup} SPI
 * is consulted on first reference to any jtreesitter class, so calling
 * {@code Language.load} here is sufficient to exercise the lookup. The lookup
 * itself extracts the bundled library to a temp file once per JVM and caches
 * it; subsequent calls reuse the same file.
 */
class NativeBuildSmokeTest {

    @Test
    @EnabledOnOs(value = OS.LINUX, architectures = "amd64")
    void linuxX86_64SharedLibraryLoadsAndParses() {
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
