package no.sikt.graphitron.model.boot;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The platform's cache convention for per-user tool state: {@code $XDG_CACHE_HOME} (falling back
 * to {@code ~/.cache}) on Linux, {@code ~/Library/Caches} on macOS, {@code %LOCALAPPDATA%} on
 * Windows. The cache convention rather than the data one because what lives here is a cache by
 * nature: rebuildable from sources, no state of record, always safe to delete.
 *
 * <p>Resolved in one place for every tool that keeps such state, so they agree on where it is: the
 * dev session's fact store keeps its per-workspace home under {@code graphitron/model}, and the
 * MCP module's build keeps its embedded docs bundles under {@code graphitron/docs-index}.
 */
public final class UserCacheRoot {

    private UserCacheRoot() {}

    /** The platform's per-user cache directory; callers resolve their own segment under it. */
    public static Path resolve() {
        Path home = Path.of(System.getProperty("user.home"));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            return localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData)
                : home.resolve("AppData").resolve("Local");
        }
        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Caches");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        return xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
            ? Path.of(xdg)
            : home.resolve(".cache");
    }
}
