package no.sikt.graphitron.rewrite.maven.watch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void writingGraphqlsFile_firesCallback(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        startWatcher(Set.of(dir), latch::countDown);

        Files.writeString(dir.resolve("schema.graphqls"), "type Query { x: Int }");

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void modifyingGraphqlsFile_firesCallback(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("schema.graphqls");
        Files.writeString(file, "type Query { x: Int }");
        var latch = new CountDownLatch(1);
        startWatcher(Set.of(dir), latch::countDown);

        Files.writeString(file, "type Query { y: Int }");

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void deletingGraphqlsFile_firesCallback(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("schema.graphqls");
        Files.writeString(file, "type Query { x: Int }");
        var latch = new CountDownLatch(1);
        startWatcher(Set.of(dir), latch::countDown);

        Files.delete(file);

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void rapidWrites_firesCallbackOnce(@TempDir Path dir) throws Exception {
        var fired = new AtomicInteger();
        var latch = new CountDownLatch(1);
        startWatcher(Set.of(dir), () -> {
            fired.incrementAndGet();
            latch.countDown();
        });

        Files.writeString(dir.resolve("a.graphqls"), "type A { x: Int }");
        Files.writeString(dir.resolve("b.graphqls"), "type B { x: Int }");
        Files.writeString(dir.resolve("c.graphqls"), "type C { x: Int }");

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        // Wait past the window again to confirm no second trigger.
        Thread.sleep(DEBOUNCE_MS + 200);
        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void nonGraphqlsFile_noCallback(@TempDir Path dir) throws Exception {
        var fired = new AtomicInteger();
        startWatcher(Set.of(dir), fired::incrementAndGet);

        Files.writeString(dir.resolve("README.md"), "hello");
        Files.writeString(dir.resolve("schema.txt"), "ignore me");

        Thread.sleep(WAIT_MS);
        assertThat(fired.get()).isZero();
    }

    @Test
    void newSubdirectory_isRegisteredAndFiresCallback(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        startWatcher(Set.of(dir), latch::countDown);

        Path sub = Files.createDirectory(dir.resolve("nested"));
        // Give the watcher time to observe the directory creation and register it.
        Thread.sleep(DEBOUNCE_MS + 200);
        Files.writeString(sub.resolve("nested.graphqls"), "type N { x: Int }");

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
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

    private static WatchEvent<?> entryCreateEvent(Path relative) {
        return new WatchEvent<Path>() {
            @Override public Kind<Path> kind() { return StandardWatchEventKinds.ENTRY_CREATE; }
            @Override public int count() { return 1; }
            @Override public Path context() { return relative; }
        };
    }
}
