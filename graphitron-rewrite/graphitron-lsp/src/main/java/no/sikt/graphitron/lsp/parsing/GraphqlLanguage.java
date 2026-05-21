package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Language;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Singleton holder for the {@code tree-sitter-graphql} language binding.
 *
 * <p>The grammar binary ships via {@code no.sikt:graphitron-tree-sitter-natives}
 * and is loaded through {@link BundledLibraryLookup}, which also probes a
 * short list of well-known install prefixes (Homebrew on macOS, vcpkg on
 * Windows, {@code /usr/local/lib} on Linux) for a system-installed
 * {@code libtree-sitter} and composes that into the SPI lookup. When the
 * probe misses and jtreesitter's own loader chain also fails, this class
 * translates the resulting {@link UnsatisfiedLinkError} (or
 * {@link RuntimeException} wrapping one) into a startup-failure that names
 * the per-platform install command, so an operator running the LSP on a
 * fresh machine sees actionable guidance rather than an opaque link error.
 *
 * <p>The translator also distinguishes "missing libtree-sitter" from "found
 * libtree-sitter but it's too old for jtreesitter 0.26's ABI": jtreesitter
 * looks up {@code ts_language_abi_version} at {@link Language#load} time, a
 * symbol introduced in tree-sitter {@code 0.25}. The most common too-old
 * case is Debian/Ubuntu apt's {@code libtree-sitter0} (pinned to
 * {@code 0.20.x}); the user-facing failure is the same opaque "Symbol not
 * found: ts_language_abi_version" on every platform. {@link #classifyInstalledRuntime}
 * probes the well-known install paths for an installed libtree-sitter file
 * and checks the symbol directly, so the two cases get different actionable
 * messages — install vs. upgrade.
 *
 * <p>The {@link Arena#global() global arena} is intentional: the
 * {@link Language} keeps its native pointer alive for the JVM's lifetime,
 * matching how the LSP uses it (one parser pool, hot reload via the
 * {@code dev} mojo's filesystem watcher rather than process recycling).
 */
public final class GraphqlLanguage {

    /** Symbol jtreesitter 0.26 looks up at {@link Language#load}; introduced in tree-sitter 0.25. */
    static final String ABI_VERSION_SYMBOL = "ts_language_abi_version";

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
                throw new IllegalStateException(translateRuntimeFailure(), e);
            }
            throw e;
        }
    }

    /**
     * Maps the {@link #classifyInstalledRuntime} result onto the operator
     * message. Package-private so the unit test can pin both branches without
     * touching the static {@link #INSTANCE} init.
     */
    static String translateRuntimeFailure() {
        return switch (classifyInstalledRuntime()) {
            case TOO_OLD -> tooOldRuntimeMessage();
            case MISSING, MODERN -> missingRuntimeMessage();
        };
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

    enum RuntimeStatus { MODERN, TOO_OLD, MISSING }

    /**
     * Probes the well-known install paths for a libtree-sitter binary and
     * checks whether it exports {@link #ABI_VERSION_SYMBOL}. Returns
     * {@link RuntimeStatus#MODERN} if found and modern, {@link RuntimeStatus#TOO_OLD}
     * if found but missing the symbol (apt {@code libtree-sitter0} 0.20.x is
     * the canonical case), {@link RuntimeStatus#MISSING} if no candidate file
     * exists. A returned {@code MODERN} here doesn't mean
     * {@link Language#load} would have worked — the file we probed may not be
     * the one jtreesitter's own loader chain picked. The caller therefore
     * collapses {@code MODERN} to the install-instructions message, since the
     * "missing" hint is still actionable for the most common unknown-failure
     * case (a libtree-sitter installed somewhere we don't probe and not on
     * the JVM's default loader path).
     */
    static RuntimeStatus classifyInstalledRuntime() {
        RuntimeStatus best = RuntimeStatus.MISSING;
        try (Arena arena = Arena.ofConfined()) {
            for (Path candidate : runtimeProbePaths()) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                try {
                    SymbolLookup lib = SymbolLookup.libraryLookup(candidate, arena);
                    if (lib.find(ABI_VERSION_SYMBOL).isPresent()) {
                        return RuntimeStatus.MODERN;
                    }
                    best = RuntimeStatus.TOO_OLD;
                } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
                    // wrong arch, malformed file, etc. — try the next candidate.
                }
            }
        }
        return best;
    }

    /**
     * Superset of {@link BundledLibraryLookup}'s SPI probe with the
     * distro-package locations the SPI deliberately doesn't include because
     * they ship the too-old 0.20.x ABI. Listing them here makes the too-old
     * diagnosis tractable on the most common Debian/Ubuntu case where apt's
     * {@code libtree-sitter0} lands at
     * {@code /usr/lib/<arch>-linux-gnu/libtree-sitter.so.0}.
     */
    static List<Path> runtimeProbePaths() {
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
                Path.of("/usr/local/lib/libtree-sitter.so.0"),
                // apt libtree-sitter0 (Debian/Ubuntu) — too old, but listed here so
                // classifyInstalledRuntime can report TOO_OLD instead of MISSING.
                Path.of("/usr/lib/x86_64-linux-gnu/libtree-sitter.so.0"),
                Path.of("/usr/lib/aarch64-linux-gnu/libtree-sitter.so.0")
            );
        }
        return List.of();
    }

    private static void addVcpkgDll(List<Path> paths, String root) {
        if (root != null && !root.isBlank()) {
            paths.add(Path.of(root, "installed", "x64-windows", "bin", "tree-sitter.dll"));
        }
    }

    static String missingRuntimeMessage() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String install;
        if (os.contains("linux")) {
            install = "Arch:    pacman -S tree-sitter\n  "
                + "Fedora:  dnf install tree-sitter\n  "
                + "Debian/Ubuntu: build v0.26.9 from source (apt's libtree-sitter0 is 0.20.x and too old):\n    "
                + "git clone --depth=1 --branch=v0.26.9 https://github.com/tree-sitter/tree-sitter /tmp/ts \\\n    "
                + "  && make -C /tmp/ts && sudo make -C /tmp/ts install && sudo ldconfig";
        } else if (os.contains("mac")) {
            install = "brew install tree-sitter";
        } else if (os.contains("windows")) {
            install = "vcpkg install tree-sitter:x64-windows  "
                + "(or a pinned upstream build, placed on PATH)";
        } else {
            install = "install libtree-sitter for your platform and ensure it is on "
                + "the OS library search path";
        }
        return "graphitron-lsp could not find libtree-sitter on this system. "
            + "The grammar binary ships with graphitron-tree-sitter-natives, but the "
            + "tree-sitter runtime itself is a system dependency. To install it:\n  "
            + install
            + "\n\nFor non-default install locations and the full per-platform table see "
            + DOCS_URL;
    }

    static String tooOldRuntimeMessage() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String upgrade;
        if (os.contains("linux")) {
            // apt's libtree-sitter0 is the overwhelmingly common too-old case;
            // name it explicitly so the user recognises their own situation.
            upgrade = "If you installed via apt (libtree-sitter0 0.20.x), uninstall it and "
                + "build v0.26.9 from source:\n  "
                + "sudo apt remove libtree-sitter0 \\\n  "
                + "  && git clone --depth=1 --branch=v0.26.9 https://github.com/tree-sitter/tree-sitter /tmp/ts \\\n  "
                + "  && make -C /tmp/ts && sudo make -C /tmp/ts install && sudo ldconfig";
        } else if (os.contains("mac")) {
            upgrade = "brew upgrade tree-sitter  (or brew install tree-sitter if not already present)";
        } else if (os.contains("windows")) {
            upgrade = "vcpkg upgrade tree-sitter:x64-windows  "
                + "(or replace the pinned upstream build on PATH with v0.25+ )";
        } else {
            upgrade = "upgrade libtree-sitter to v0.25 or later for your platform";
        }
        return "graphitron-lsp found libtree-sitter on this system but it is too old: "
            + "jtreesitter 0.26 requires the " + ABI_VERSION_SYMBOL + " symbol, "
            + "introduced in tree-sitter 0.25. Your installed library predates this "
            + "(commonly Debian/Ubuntu apt's libtree-sitter0, which is pinned to "
            + "0.20.x). To upgrade:\n  "
            + upgrade
            + "\n\nFor the full per-platform table see "
            + DOCS_URL;
    }

    private static final String DOCS_URL =
        "https://graphitron.sikt.no/getting-started.html#native-runtime-dependency";
}
