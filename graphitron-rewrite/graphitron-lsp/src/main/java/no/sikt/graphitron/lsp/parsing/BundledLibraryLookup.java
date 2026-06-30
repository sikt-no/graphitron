package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.NativeLibraryLookup;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Resolves both native pieces of the tree-sitter GraphQL parser against the
 * per-platform shared libraries that ship in
 * {@code no.sikt:graphitron-tree-sitter-natives}: the grammar (exporting
 * {@code tree_sitter_graphql}) and the tree-sitter runtime
 * ({@code libtree-sitter}, exporting the {@code ts_*} symbols). The natives jar
 * carries one of each per supported platform under {@code lib/<os>-<arch>/}
 * ({@code libtree-sitter-graphql.{so,dylib}} + {@code libtree-sitter.{so,dylib}}
 * on POSIX; {@code tree-sitter-graphql.dll} + {@code tree-sitter.dll} on
 * Windows).
 *
 * <p>This class is the registered jtreesitter {@link NativeLibraryLookup} SPI
 * service (see {@code META-INF/services/io.github.treesitter.jtreesitter.NativeLibraryLookup}).
 * jtreesitter calls it at {@code TreeSitter} class-init to bind the runtime
 * {@code ts_*} symbols, and {@link GraphqlLanguage} also passes its result to
 * {@link io.github.treesitter.jtreesitter.Language#load} to resolve the grammar
 * entry point. The returned lookup composes {@code grammar.or(runtime)}, which
 * serves both roles at once: the runtime half supplies {@code ts_*}, the grammar
 * half supplies {@code tree_sitter_graphql}.
 *
 * <p>Both libraries are extracted from the classpath to temporary files the
 * first time the lookup is asked for them; subsequent lookups reuse the same
 * files. The {@link Arena} jtreesitter passes in keeps the mappings alive for
 * the JVM's lifetime.
 *
 * <p>Because the runtime is bundled, the LSP has <em>no</em> native system
 * dependency: there is nothing to install. An unsupported host architecture
 * fails fast at {@link #resolvePlatform} with an
 * {@link UnsupportedOperationException} naming the {@code os.name} /
 * {@code os.arch} it saw and the supported set. A load failure of the bundled
 * runtime (corrupt extract, a {@code noexec} temp dir, a missing system C
 * runtime) surfaces through {@link GraphqlLanguage}'s single
 * bundled-load-failure diagnostic.
 */
public final class BundledLibraryLookup implements NativeLibraryLookup {

    private record Platform(String dir, String grammar, String runtime) {}

    private static final Platform PLATFORM = resolvePlatform();

    /** Package-private so tests can reuse the resolved resource paths. */
    static final String RESOURCE_PATH = "lib/" + PLATFORM.dir() + "/" + PLATFORM.grammar();
    static final String RUNTIME_RESOURCE_PATH = "lib/" + PLATFORM.dir() + "/" + PLATFORM.runtime();

    private static volatile Path extractedGrammar;
    private static volatile Path extractedRuntime;

    // SymbolLookup.libraryLookup is a restricted FFM method; runtime native access is declared
    // via --enable-native-access=ALL-UNNAMED (graphitron-lsp/pom.xml surefire argLine).
    @SuppressWarnings("restricted")
    @Override
    public SymbolLookup get(Arena arena) {
        SymbolLookup grammar = SymbolLookup.libraryLookup(extractGrammar(), arena);
        SymbolLookup runtime = SymbolLookup.libraryLookup(extractRuntime(), arena);
        return grammar.or(runtime);
    }

    /**
     * The temp file the bundled runtime was extracted to, or {@code null} if
     * extraction has not run yet or failed before producing a file. Used by
     * {@link GraphqlLanguage} to name the path in its load-failure diagnostic.
     */
    static Path extractedRuntimePath() {
        return extractedRuntime;
    }

    private static Path extractGrammar() {
        Path cached = extractedGrammar;
        if (cached != null) {
            return cached;
        }
        synchronized (BundledLibraryLookup.class) {
            if (extractedGrammar == null) {
                extractedGrammar = extract(RESOURCE_PATH);
            }
            return extractedGrammar;
        }
    }

    private static Path extractRuntime() {
        Path cached = extractedRuntime;
        if (cached != null) {
            return cached;
        }
        synchronized (BundledLibraryLookup.class) {
            if (extractedRuntime == null) {
                extractedRuntime = extract(RUNTIME_RESOURCE_PATH);
            }
            return extractedRuntime;
        }
    }

    private static Path extract(String resourcePath) {
        try {
            String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            Path target = Files.createTempFile("graphitron-lsp-", "-" + fileName);
            target.toFile().deleteOnExit();
            ClassLoader cl = BundledLibraryLookup.class.getClassLoader();
            try (InputStream in = cl.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IllegalStateException("missing classpath resource: " + resourcePath
                        + "; the graphitron-tree-sitter-natives jar does not appear to be on the classpath");
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("failed to extract bundled tree-sitter library " + resourcePath, e);
        }
    }

    private static Platform resolvePlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            return new Platform("linux-x86_64", "libtree-sitter-graphql.so", "libtree-sitter.so");
        } else if (os.contains("linux") && arch.equals("aarch64")) {
            return new Platform("linux-aarch64", "libtree-sitter-graphql.so", "libtree-sitter.so");
        } else if (os.contains("mac") && arch.equals("aarch64")) {
            return new Platform("macos-aarch64", "libtree-sitter-graphql.dylib", "libtree-sitter.dylib");
        } else if (os.contains("windows") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            // Windows DLLs follow platform convention: no "lib" prefix.
            return new Platform("windows-x86_64", "tree-sitter-graphql.dll", "tree-sitter.dll");
        }
        throw new UnsupportedOperationException(
            "no graphitron-tree-sitter-natives binaries for os.name=" + os
                + ", os.arch=" + arch + "; supported: linux-x86_64, linux-aarch64, "
                + "macos-aarch64, windows-x86_64.");
    }

    /** ServiceLoader requires a public no-arg constructor. */
    public BundledLibraryLookup() {}
}
