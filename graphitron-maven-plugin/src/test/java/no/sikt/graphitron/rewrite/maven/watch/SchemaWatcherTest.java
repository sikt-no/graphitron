package no.sikt.graphitron.rewrite.maven.watch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchService;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaWatcherTest {

    private static final long DEBOUNCE_MS = 100;
    private static final long WAIT_MS = DEBOUNCE_MS + 1500;

    private DebounceExecutor debounce;
    private SchemaWatcher watcher;
    private Thread watcherThread;

    @AfterEach
    void tearDown() throws Exception {
        if (watcher != null) watcher.close();
        if (debounce != null) debounce.close();
        if (watcherThread != null) watcherThread.join(2000);
    }

    /**
     * inotify integration smoke. Linux-only by design: macOS's JDK ships
     * {@code PollingWatchService} with a hardcoded 10 s period (since the
     * removal of {@code SensitivityWatchEventModifier} in JDK 21), so the
     * 1.6 s wait would always time out. The logic this asserts ; suffix
     * filter, debounce wire-up, on-the-fly subdirectory registration ; is
     * covered cross-platform by the synthetic-dispatch tests below.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void writingGraphqlsFile_firesCallback(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        startWatcher(Set.of(dir), latch::countDown);

        Files.writeString(dir.resolve("schema.graphqls"), "type Query { x: Int }");

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void modifyingGraphqlsFile_firesCallback(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, latch::countDown);

        watcher.dispatch(dir, entryModifyEvent(Path.of("schema.graphqls")));

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void deletingGraphqlsFile_firesCallback(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, latch::countDown);

        watcher.dispatch(dir, entryDeleteEvent(Path.of("schema.graphqls")));

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void rapidWrites_firesCallbackOnce(@TempDir Path dir) throws Exception {
        var fired = new AtomicInteger();
        var latch = new CountDownLatch(1);
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, () -> {
            fired.incrementAndGet();
            latch.countDown();
        });

        watcher.dispatch(dir, entryCreateEvent(Path.of("a.graphqls")));
        watcher.dispatch(dir, entryCreateEvent(Path.of("b.graphqls")));
        watcher.dispatch(dir, entryCreateEvent(Path.of("c.graphqls")));

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        // Wait past the debounce window again to confirm the three dispatches
        // collapsed to a single callback (debounce coalescing is its own
        // contract; we pin SchemaWatcher's side of the wire-up).
        Thread.sleep(DEBOUNCE_MS + 200);
        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void newSubdirectory_isRegisteredAndFiresCallback(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, latch::countDown);

        // Real directory so the dispatcher's Files.isDirectory check succeeds and
        // the subtree gets registered; events themselves are synthetic so the test
        // does not wait on the OS-level WatchService.
        Path sub = Files.createDirectory(dir.resolve("nested"));
        watcher.dispatch(dir, entryCreateEvent(Path.of("nested")));
        watcher.dispatch(sub, entryModifyEvent(Path.of("nested.graphqls")));

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(watcher.watchedDirs()).contains(dir, sub);
    }

    @Test
    void overflowEvent_firesCallback(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, latch::countDown);

        watcher.dispatch(dir, overflowEvent());

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void addRootRacesWithDispatch_bothRegistrationsLand(@TempDir Path dir) throws Exception {
        // The registry is shared between the watch-loop thread (writes from
        // dispatch on ENTRY_CREATE-for-directory) and the debounce thread
        // (writes from addRoot). Pin that concurrent registration is safe.
        Path createdViaDispatch = Files.createDirectory(dir.resolve("via-dispatch"));
        Path createdViaAddRoot = Files.createDirectory(dir.resolve("via-add-root"));

        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, () -> {});

        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(2);
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

        Runnable dispatchSide = () -> {
            try {
                start.await();
                watcher.dispatch(dir, entryCreateEvent(dir.relativize(createdViaDispatch)));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        };
        Runnable addRootSide = () -> {
            try {
                start.await();
                watcher.addRoot(createdViaAddRoot);
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        };

        new Thread(dispatchSide, "race-dispatch").start();
        new Thread(addRootSide, "race-addroot").start();
        start.countDown();

        assertThat(done.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(errors).isEmpty();
        assertThat(watcher.watchedDirs()).contains(dir, createdViaDispatch, createdViaAddRoot);
    }

    @Test
    void dispatch_triggersOnDotGraphql_whenConfigured(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, latch::countDown,
            Set.of(".graphqls", ".graphql"));

        watcher.dispatch(dir, entryModifyEvent(Path.of("a.graphql")));

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void dispatch_ignoresUnconfiguredSuffix(@TempDir Path dir) throws Exception {
        var fired = new AtomicInteger();
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, fired::incrementAndGet,
            Set.of(".graphqls"));

        watcher.dispatch(dir, entryModifyEvent(Path.of("a.graphql")));

        Thread.sleep(WAIT_MS);
        assertThat(fired.get()).isZero();
    }

    @Test
    void constructor_emptySuffixSet_rejected(@TempDir Path dir) {
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        assertThatThrownBy(
            () -> new SchemaWatcher(Set.of(dir), debounce, () -> {}, Set.<String>of())
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Backend-probe ratchet. The synthetic-dispatch lift turns on the
     * assumption that macOS's WatchService is polling-only ; if a future JDK
     * ships an FSEvents-backed WatchService, this test fails loudly and the
     * Linux-only smoke gate above gets revisited. The Linux check is paired
     * as a sanity ratchet; the JDK's current Linux WatchService class is
     * {@code sun.nio.fs.LinuxWatchService}.
     */
    @Test
    void watchServiceBackend_matchesExpectedPerOs() throws Exception {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            String name = ws.getClass().getSimpleName();
            if (OS.MAC.isCurrentOs()) {
                assertThat(name)
                    .as("macOS WatchService is expected to be polling-only; "
                        + "a non-polling backend invalidates R198's Linux-only smoke gate")
                    .isEqualTo("PollingWatchService");
            } else if (OS.LINUX.isCurrentOs()) {
                assertThat(name)
                    .as("Linux WatchService is expected to be inotify-backed")
                    .isEqualTo("LinuxWatchService");
            }
            // Other OSes (Windows, BSD) are out of scope for this ratchet; do not assert here.
        }
    }

    private void startWatcher(Set<Path> roots, Runnable onTrigger) throws Exception {
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(roots, debounce, onTrigger);
        watcherThread = new Thread(watcher::run, "test-schema-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private static WatchEvent<?> overflowEvent() {
        return new WatchEvent<>() {
            @Override public Kind<Object> kind() { return StandardWatchEventKinds.OVERFLOW; }
            @Override public int count() { return 1; }
            @Override public Object context() { return null; }
        };
    }

    private static WatchEvent<?> entryModifyEvent(Path relative) {
        return new WatchEvent<Path>() {
            @Override public Kind<Path> kind() { return StandardWatchEventKinds.ENTRY_MODIFY; }
            @Override public int count() { return 1; }
            @Override public Path context() { return relative; }
        };
    }

    private static WatchEvent<?> entryCreateEvent(Path relative) {
        return new WatchEvent<Path>() {
            @Override public Kind<Path> kind() { return StandardWatchEventKinds.ENTRY_CREATE; }
            @Override public int count() { return 1; }
            @Override public Path context() { return relative; }
        };
    }

    private static WatchEvent<?> entryDeleteEvent(Path relative) {
        return new WatchEvent<Path>() {
            @Override public Kind<Path> kind() { return StandardWatchEventKinds.ENTRY_DELETE; }
            @Override public int count() { return 1; }
            @Override public Path context() { return relative; }
        };
    }
}
