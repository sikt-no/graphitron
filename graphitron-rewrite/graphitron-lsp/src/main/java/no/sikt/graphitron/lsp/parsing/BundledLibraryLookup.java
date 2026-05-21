package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.NativeLibraryLookup;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
 * <p>To shorten the install path on hosts whose package manager lands
 * {@code libtree-sitter} outside the JVM's default loader search
 * (Apple-silicon Homebrew's {@code /opt/homebrew/lib}, vcpkg's
 * {@code <VCPKG_ROOT>/installed/x64-windows/bin}), this class also probes a
 * short list of well-known install prefixes and, if a {@code libtree-sitter}
 * is found there, composes its lookup onto the grammar lookup via
 * {@link SymbolLookup#or}. The probe is best-effort: if nothing matches,
 * jtreesitter's own chain still runs and a system-path install (Arch
 * {@code pacman}, Fedora {@code dnf}, Linux source-build into
 * {@code /usr/local/lib} with {@code ldconfig}) keeps working as before.
 * The probe lets the most common Homebrew + vcpkg layouts work without any
 * {@code JAVA_TOOL_OPTIONS} / {@code PATH} wiring on the consumer's side.
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
        SymbolLookup grammar = SymbolLookup.libraryLookup(extractOnce(), arena);
        return probeSystemTreeSitter(arena)
            .map(grammar::or)
            .orElse(grammar);
    }

    /**
     * Best-effort lookup of a system-installed {@code libtree-sitter} from a
     * short list of well-known install prefixes per host. Returns the first
     * one that loads; empty if none match. Failures (file missing, dlopen
     * error, wrong architecture) are silent — the SPI fallback in
     * jtreesitter still gets to run, and {@link GraphqlLanguage} surfaces
     * a clean missing-runtime message if nothing finds the library.
     */
    private static Optional<SymbolLookup> probeSystemTreeSitter(Arena arena) {
        for (Path candidate : candidateRuntimePaths()) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                return Optional.of(SymbolLookup.libraryLookup(candidate, arena));
            } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
                // wrong arch, malformed lib, etc. — try the next candidate.
            }
        }
        return Optional.empty();
    }

    private static List<Path> candidateRuntimePaths() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return List.of(
                Path.of("/opt/homebrew/lib/libtree-sitter.dylib"),
                Path.of("/usr/local/lib/libtree-sitter.dylib")
            );
        }
        if (os.contains("windows")) {
            List<Path> paths = new ArrayList<>();
            addVcpkgDll(paths, System.getenv("VCPKG_ROOT"));
            addVcpkgDll(paths, System.getenv("VCPKG_INSTALLATION_ROOT"));
            paths.add(Path.of("C:\\vcpkg\\installed\\x64-windows\\bin\\tree-sitter.dll"));
            return List.copyOf(paths);
        }
        if (os.contains("linux")) {
            return List.of(
                Path.of("/usr/local/lib/libtree-sitter.so"),
                Path.of("/usr/local/lib/libtree-sitter.so.0")
            );
        }
        return List.of();
    }

    private static void addVcpkgDll(List<Path> paths, String root) {
        if (root != null && !root.isBlank()) {
            paths.add(Path.of(root, "installed", "x64-windows", "bin", "tree-sitter.dll"));
        }
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
