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
 * Resolves the {@code tree_sitter_graphql} grammar entry point against the
 * per-platform shared library that ships in
 * {@code no.sikt:graphitron-tree-sitter-natives}. The natives jar carries one
 * grammar binary per supported platform under
 * {@code lib/<os>-<arch>/libtree-sitter-graphql.{so,dylib}} (POSIX) or
 * {@code lib/<os>-<arch>/tree-sitter-graphql.dll} (Windows).
 *
 * <p>The tree-sitter runtime itself ({@code libtree-sitter}, exporting the
 * {@code ts_*} symbols) is <em>not</em> bundled. jtreesitter's
 * {@code package-info} documents the three loading mechanisms it composes
 * for the runtime: registered {@link NativeLibraryLookup} SPI services first
 * (this class is one), {@code SymbolLookup.libraryLookup} on the OS search
 * path / {@code java.library.path}, then the JVM-default
 * {@code Linker.defaultLookup}. This class contributes the grammar; the
 * runtime is sourced from the consumer's OS install (apt
 * {@code libtree-sitter0} on Linux, brew {@code tree-sitter} on macOS,
 * vcpkg or pinned upstream build on Windows). When the system runtime is
 * missing, {@link GraphqlLanguage} surfaces a startup-failure that names
 * the per-platform install command rather than letting jtreesitter raise
 * an opaque {@link UnsatisfiedLinkError}.
 *
 * <p>The grammar library is extracted from the classpath to a temporary
 * file the first time the lookup is asked for it; subsequent lookups reuse
 * the same file. The {@link Arena} jtreesitter passes in keeps the mapping
 * alive for the JVM's lifetime.
 */
public final class BundledLibraryLookup implements NativeLibraryLookup {

    /** Package-private so tests can reuse the resolver. */
    static final String RESOURCE_PATH = resolveResourcePath();

    private static volatile Path extractedLibrary;

    @Override
    public SymbolLookup get(Arena arena) {
        return SymbolLookup.libraryLookup(extractOnce(), arena);
    }

    private static Path extractOnce() {
        Path cached = extractedLibrary;
        if (cached != null) {
            return cached;
        }
        synchronized (BundledLibraryLookup.class) {
            if (extractedLibrary != null) {
                return extractedLibrary;
            }
            try {
                String fileName = RESOURCE_PATH.substring(RESOURCE_PATH.lastIndexOf('/') + 1);
                Path target = Files.createTempFile("graphitron-lsp-", "-" + fileName);
                target.toFile().deleteOnExit();
                ClassLoader cl = BundledLibraryLookup.class.getClassLoader();
                try (InputStream in = cl.getResourceAsStream(RESOURCE_PATH)) {
                    if (in == null) {
                        throw new IllegalStateException("missing classpath resource: " + RESOURCE_PATH
                            + "; the graphitron-tree-sitter-natives jar does not appear to be on the classpath");
                    }
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                extractedLibrary = target;
                return target;
            } catch (IOException e) {
                throw new IllegalStateException("failed to extract bundled tree-sitter library", e);
            }
        }
    }

    private static String resolveResourcePath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dir;
        String libName;
        if (os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            dir = "linux-x86_64";
            libName = "libtree-sitter-graphql.so";
        } else if (os.contains("linux") && arch.equals("aarch64")) {
            dir = "linux-aarch64";
            libName = "libtree-sitter-graphql.so";
        } else if (os.contains("mac") && arch.equals("aarch64")) {
            dir = "macos-aarch64";
            libName = "libtree-sitter-graphql.dylib";
        } else if (os.contains("windows") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            dir = "windows-x86_64";
            // Windows DLLs follow platform convention: no "lib" prefix.
            libName = "tree-sitter-graphql.dll";
        } else {
            throw new UnsupportedOperationException(
                "no graphitron-tree-sitter-natives grammar binary for os.name=" + os
                    + ", os.arch=" + arch + "; supported: linux-x86_64, linux-aarch64, "
                    + "macos-aarch64, windows-x86_64.");
        }
        return "lib/" + dir + "/" + libName;
    }

    /** ServiceLoader requires a public no-arg constructor. */
    public BundledLibraryLookup() {}
}
