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
 * Resolves jtreesitter's runtime symbols against the per-platform shared
 * library this module ships under
 * {@code lib/<os>-<arch>/libtree-sitter-graphql.{so,dylib,dll}}. That library
 * is the unity build of the vendored tree-sitter runtime + the bkegley
 * tree-sitter-graphql grammar, so it exports both the {@code ts_*} runtime
 * functions jtreesitter looks up at static-init time and the
 * {@code tree_sitter_graphql} entry point {@link GraphqlLanguage} loads.
 *
 * <p>jtreesitter's default {@code ChainedLibraryLookup} (an implementation
 * detail of the {@code internal} package) consults registered
 * {@link NativeLibraryLookup} services first; this class is registered via
 * {@code META-INF/services/io.github.treesitter.jtreesitter.NativeLibraryLookup}
 * so its lookup runs before jtreesitter falls back to looking for a separate
 * {@code libtree-sitter} on the {@code java.library.path}.
 *
 * <p>The library is extracted from the classpath to a temporary file the
 * first time the lookup is asked for it; subsequent lookups reuse the same
 * file. This is the standard pattern for shipping a native lib inside a jar
 * and the {@code Arena} jtreesitter passes in keeps the mapping alive for
 * the JVM's lifetime.
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
                            + "; the build-native-* Maven profile did not run for this platform");
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
        } else if (os.contains("mac") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            dir = "macos-x86_64";
            libName = "libtree-sitter-graphql.dylib";
        } else if (os.contains("mac") && arch.equals("aarch64")) {
            dir = "macos-aarch64";
            libName = "libtree-sitter-graphql.dylib";
        } else {
            // Windows lands in a follow-up; until build-native.bat and the matching
            // Maven profile exist, fail fast with a message that points at the
            // missing build infrastructure rather than a confusing
            // UnsatisfiedLinkError later.
            throw new UnsupportedOperationException(
                "no bundled tree-sitter-graphql native library for os.name=" + os
                    + ", os.arch=" + arch + "; supported: linux-x86_64, macos-x86_64, "
                    + "macos-aarch64. Windows is a follow-up to R18 Phase 6.");
        }
        return "lib/" + dir + "/" + libName;
    }

    /** ServiceLoader requires a public no-arg constructor. */
    public BundledLibraryLookup() {}
}
