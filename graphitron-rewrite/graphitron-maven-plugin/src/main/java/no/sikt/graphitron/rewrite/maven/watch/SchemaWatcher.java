package no.sikt.graphitron.rewrite.maven.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Watches a set of directories for filename-suffix-matching changes and routes a debounced
 * trigger to the supplied callback on every relevant event.
 *
 * <p>Event-loop thread:
 * <ol>
 *   <li>{@link #run()} blocks on {@link WatchService#take()} until the service is closed.</li>
 *   <li>Each {@link WatchKey}'s events are inspected. Events whose context ends in one of the
 *       configured filename suffixes schedule a debounced trigger. {@code OVERFLOW} also
 *       schedules a trigger so a burst of events does not silently skip a regeneration.
 *       Newly-created directories are registered on the fly so subtrees added during the watch
 *       session are observed.</li>
 *   <li>Closing the watcher (via {@link #close()}) cancels {@code take()} with
 *       {@link ClosedWatchServiceException}, which {@link #run()} treats as graceful exit.</li>
 * </ol>
 */
public final class SchemaWatcher implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaWatcher.class);

    /** Default suffix for the schema-input watch loop. */
    public static final String GRAPHQLS_SUFFIX = ".graphqls";

    private final WatchService watchService;
    private final DebounceExecutor debounce;
    private final Runnable onTrigger;
    private final Set<String> filenameSuffixes;
    /**
     * Shared between the watch-loop thread (reads in {@link #run()}, writes from
     * {@link #dispatch} on directory-create) and the debounce-executor thread
     * (writes from {@link #addRoot}). Concurrent map so put/get/remove are
     * thread-safe without external synchronization; {@code containsValue} is
     * O(n) but called only on the trigger path (cheap relative to a generator
     * run).
     */
    private final Map<WatchKey, Path> registry = new ConcurrentHashMap<>();

    public SchemaWatcher(Set<Path> roots, DebounceExecutor debounce, Runnable onTrigger) throws IOException {
        this(roots, debounce, onTrigger, Set.of(GRAPHQLS_SUFFIX));
    }

    /**
     * Single-suffix variant. Used by the dev goal's catalog-refresh watcher to listen on
     * {@code .class} files under the consumer's compiled jOOQ output.
     */
    public SchemaWatcher(Set<Path> roots, DebounceExecutor debounce, Runnable onTrigger, String filenameSuffix) throws IOException {
        this(roots, debounce, onTrigger, Set.of(filenameSuffix));
    }

    /**
     * Same watch contract, parameterised by a set of filename suffixes. An event fires when the
     * filename ends in any one of the configured suffixes. Used by the schema-input watcher to
     * accept the configured set of schema file extensions.
     */
    public SchemaWatcher(Set<Path> roots, DebounceExecutor debounce, Runnable onTrigger, Set<String> filenameSuffixes) throws IOException {
        if (filenameSuffixes == null || filenameSuffixes.isEmpty()) {
            throw new IllegalArgumentException("filenameSuffixes must contain at least one entry");
        }
        this.watchService = FileSystems.getDefault().newWatchService();
        this.debounce = debounce;
        this.onTrigger = onTrigger;
        this.filenameSuffixes = Set.copyOf(filenameSuffixes);
        for (Path root : roots) {
            registerRecursive(root);
        }
        if (watchService.getClass().getSimpleName().contains("Polling")) {
            LOGGER.info(
                "graphitron:dev: JDK provides a polling WatchService on this platform; schema file change latency ≈ 10 s. "
                    + "Connect an editor with the Graphitron LSP for event-driven regen.");
        }
    }

    /** Registers {@code root} and every existing subdirectory beneath it. */
    private void registerRecursive(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            LOGGER.warn("graphitron:dev: skipping non-directory watch root: {}", root);
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory).forEach(dir -> {
                try {
                    register(dir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to register watch on " + dir, e);
                }
            });
        }
    }

    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE);
        registry.put(key, dir);
    }

    /**
     * Adds {@code root} to the watch set if it is not already present. Newly-discovered
     * watch directories surfaced by a re-expansion of {@code <schemaInputs>} flow through
     * here.
     */
    public void addRoot(Path root) throws IOException {
        if (registry.containsValue(root)) return;
        registerRecursive(root);
    }

    /**
     * Blocks the calling thread, dispatching events until the watcher is closed.
     */
    public void run() {
        try {
            while (true) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (ClosedWatchServiceException | InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Path dir = registry.get(key);
                for (WatchEvent<?> event : key.pollEvents()) {
                    dispatch(dir, event);
                }
                if (!key.reset()) {
                    registry.remove(key);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("graphitron:dev: event loop terminated abnormally", e);
            throw e;
        }
    }

    /**
     * Test seam: drives a synthetic event into the dispatcher without waiting on the
     * OS-level {@link WatchService}. Production callers reach this only via the
     * {@link #run()} loop; this is the visible entry point used by tests that pin
     * unit-tier invariants on the dispatch logic itself (suffix filter, OVERFLOW
     * reschedule, on-the-fly subdirectory registration).
     */
    public void dispatch(Path dir, WatchEvent<?> event) {
        WatchEvent.Kind<?> kind = event.kind();
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            LOGGER.info("graphitron:dev: OVERFLOW; rescheduling regeneration");
            debounce.schedule(onTrigger);
            return;
        }
        Object context = event.context();
        if (!(context instanceof Path relative)) return;
        Path resolved = dir != null ? dir.resolve(relative) : relative;
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(resolved)) {
            try {
                registerRecursive(resolved);
            } catch (IOException e) {
                LOGGER.warn("graphitron:dev: failed to register new directory {}: {}", resolved, e.getMessage());
            }
            return;
        }
        String name = relative.toString();
        for (String suffix : filenameSuffixes) {
            if (name.endsWith(suffix)) {
                debounce.schedule(onTrigger);
                return;
            }
        }
    }

    @Override
    public void close() {
        try {
            watchService.close();
        } catch (IOException e) {
            LOGGER.warn("graphitron:dev: error closing WatchService: {}", e.getMessage());
        }
    }

    /** Snapshot of currently-registered watch directories. Test seam. */
    Set<Path> watchedDirs() {
        return Set.copyOf(registry.values());
    }
}
