package no.sikt.graphitron.rewrite.maven.watch;

import no.sikt.graphitron.model.sources.Observation;
import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaWatcherTest {

    private static final long DEBOUNCE_MS = 100;
    private static final long WAIT_MS = DEBOUNCE_MS + 1500;

    /**
     * One store for the class. The observation cases read the roster through it and write nothing,
     * so a store per case would be six boots for one query, against a module-wide boot budget.
     */
    @RegisterExtension
    static final FactStores.ClassStore STORE = FactStores.perClass();

    /** The corpus the schema watcher covers, as {@code meta_gatherer_corpus} names it. */
    private static final String SDL = "sdl";

    private DebounceExecutor debounce;
    private SchemaWatcher watcher;
    private Thread watcherThread;
    // Watching nothing, for the cases whose subject is the trigger rather than what the watcher
    // was told. The observation cases below build one of their own over a store.
    private WatchedCorpus watched = WatchedCorpus.unobserved(SDL);

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
        watcher = new SchemaWatcher(Set.of(dir), debounce, latch::countDown, watched);

        watcher.dispatch(dir, entryModifyEvent(Path.of("schema.graphqls")));

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void deletingGraphqlsFile_firesCallback(@TempDir Path dir) throws Exception {
        var latch = new CountDownLatch(1);
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, latch::countDown, watched);

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
        }, watched);

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
        watcher = new SchemaWatcher(Set.of(dir), debounce, latch::countDown, watched);

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
        watcher = new SchemaWatcher(Set.of(dir), debounce, latch::countDown, watched);

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
        watcher = new SchemaWatcher(Set.of(dir), debounce, () -> {}, watched);

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
            Set.of(".graphqls", ".graphql"), watched);

        watcher.dispatch(dir, entryModifyEvent(Path.of("a.graphql")));

        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void dispatch_ignoresUnconfiguredSuffix(@TempDir Path dir) throws Exception {
        var fired = new AtomicInteger();
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, fired::incrementAndGet,
            Set.of(".graphqls"), watched);

        watcher.dispatch(dir, entryModifyEvent(Path.of("a.graphql")));

        Thread.sleep(WAIT_MS);
        assertThat(fired.get()).isZero();
    }

    @Test
    void constructor_emptySuffixSet_rejected(@TempDir Path dir) {
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        assertThatThrownBy(
            () -> new SchemaWatcher(Set.of(dir), debounce, () -> {}, Set.<String>of(), watched)
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
        watcher = new SchemaWatcher(roots, debounce, onTrigger, watched);
        watcherThread = new Thread(watcher::run, "test-schema-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    /**
     * The event's own information, which this loop used to drop on the next line: the resolved
     * path went out of scope and the stages downstream rediscovered it by reading bytes. Each case
     * here asserts through the comparison a reader actually makes rather than through a mark
     * counter, so a watcher that recorded something unusable would still fail.
     *
     * <p>Each loss case asserts an instant is trusted before the event and distrusted after it,
     * rather than only the second half. A one-sided assertion passes when the instant was below
     * the floor all along, which is the way a case like this goes quietly vacuous.
     */
    @Test
    @DisplayName("a suffix-matching change marks the resolved path before it schedules")
    void dispatchMarksTheResolvedPath(@TempDir Path dir) throws Exception {
        {
            var latch = new CountDownLatch(1);
            var observation = watcherOver(dir, latch::countDown);
            Path schema = dir.resolve("schema.graphqls");
            var readBeforeTheSave = between();
            assertThat(observation.trusts(SDL, schema.toString(), readBeforeTheSave)).isTrue();

            watcher.dispatch(dir, entryModifyEvent(Path.of("schema.graphqls")));

            assertThat(observation.trusts(SDL, schema.toString(), readBeforeTheSave))
                .as("the file the watcher resolved is the instance a reader will distrust")
                .isFalse();
            assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS))
                .as("and the trigger is still scheduled, the mark riding in front of it")
                .isTrue();
        }
    }

    @Test
    @DisplayName("a delete marks the file it removed")
    void dispatchMarksADelete(@TempDir Path dir) throws Exception {
        {
            var observation = watcherOver(dir, () -> { });
            Path schema = dir.resolve("schema.graphqls");
            var readBeforeTheDelete = between();
            assertThat(observation.trusts(SDL, schema.toString(), readBeforeTheDelete)).isTrue();

            watcher.dispatch(dir, entryDeleteEvent(Path.of("schema.graphqls")));

            assertThat(observation.trusts(SDL, schema.toString(), readBeforeTheDelete))
                .as("a file that left is a change like any other; its rows describe nothing now")
                .isFalse();
        }
    }

    @Test
    @DisplayName("OVERFLOW gives up the corpus and still schedules")
    void overflowLosesTheCorpus(@TempDir Path dir) throws Exception {
        {
            var latch = new CountDownLatch(1);
            var observation = watcherOver(dir, latch::countDown);
            Path schema = dir.resolve("schema.graphqls");
            var readBeforeTheOverflow = between();
            assertThat(observation.trusts(SDL, schema.toString(), readBeforeTheOverflow)).isTrue();

            watcher.dispatch(dir, overflowEvent());

            assertThat(observation.trusts(SDL, schema.toString(), readBeforeTheOverflow))
                .as("the events nobody received are the ones nothing can name, so the corpus goes"
                    + " cold rather than reading as unchanged")
                .isFalse();
            assertThat(observation.lossReason(SDL)).isEqualTo("OVERFLOW");
            assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("a subtree registered mid-session gives up the corpus")
    void registeringANewDirectoryLosesTheCorpus(@TempDir Path dir) throws Exception {
        {
            var observation = watcherOver(dir, () -> { });
            Path schema = dir.resolve("schema.graphqls");
            var readBefore = between();
            assertThat(observation.trusts(SDL, schema.toString(), readBefore)).isTrue();
            Files.createDirectory(dir.resolve("nested"));

            watcher.dispatch(dir, entryCreateEvent(Path.of("nested")));

            assertThat(observation.trusts(SDL, schema.toString(), readBefore))
                .as("whatever the subtree already held arrived unwatched")
                .isFalse();
            assertThat(observation.lossReason(SDL)).contains("new directory");
        }
    }

    @Test
    @DisplayName("adding a watch root mid-session gives up the corpus")
    void addRootLosesTheCorpus(@TempDir Path dir) throws Exception {
        {
            var observation = watcherOver(dir, () -> { });
            Path schema = dir.resolve("schema.graphqls");
            var readBefore = between();
            assertThat(observation.trusts(SDL, schema.toString(), readBefore)).isTrue();
            Path added = Files.createDirectory(dir.resolve("added"));

            watcher.addRoot(added);

            assertThat(observation.trusts(SDL, schema.toString(), readBefore))
                .as("a root that was not being watched until now covers files read blind")
                .isFalse();
            assertThat(observation.lossReason(SDL)).contains("watch root added");
        }
    }

    @Test
    @DisplayName("a watcher says it is watching only once its registrations are in place")
    void observingFollowsRegistration(@TempDir Path dir) throws Exception {
        {
            var observation = new Observation(STORE.handle().dsl());
            observation.register(SDL, List.of(dir), Path::toString);
            watched = new WatchedCorpus(observation, SDL, RecentChanges.none());
            Path schema = dir.resolve("schema.graphqls");
            assertThat(observation.trusts(SDL, schema.toString(), between()))
                .as("declared and unwatched, so nothing read establishes anything")
                .isFalse();

            debounce = new DebounceExecutor(DEBOUNCE_MS);
            watcher = new SchemaWatcher(Set.of(dir), debounce, () -> { }, watched);

            assertThat(observation.trusts(SDL, schema.toString(), between()))
                .as("and watched from the moment the constructor's registrations are in")
                .isTrue();
        }
    }

    @Test
    @DisplayName("the ring names the file a round was told about, and drains when it is read")
    void theRingNamesTheChangedFile(@TempDir Path dir) throws Exception {
        {
            var recent = new RecentChanges(dir);
            var observation = new Observation(STORE.handle().dsl());
            observation.register(SDL, List.of(dir), Path::toString);
            watched = new WatchedCorpus(observation, SDL, recent);
            debounce = new DebounceExecutor(DEBOUNCE_MS);
            watcher = new SchemaWatcher(Set.of(dir), debounce, () -> { }, watched);

            watcher.dispatch(dir, entryModifyEvent(Path.of("schema.graphqls")));

            assertThat(recent.drain(SDL))
                .as("a round says what it was told rather than only that it ran")
                .hasValue("1 changed file: schema.graphqls");
            assertThat(recent.drain(SDL))
                .as("and the round after it does not repeat what this one already said")
                .isEmpty();
        }
    }

    /**
     * A watcher over {@code dir} whose corpus is declared and watched, with {@link #watched}
     * pointed at the returned observation. Registration precedes construction because the
     * constructor is what says a watcher is up, and a fold has to exist by then.
     */
    private Observation watcherOver(Path dir, Runnable onTrigger) throws java.io.IOException {
        var observation = new Observation(STORE.handle().dsl());
        observation.register(SDL, List.of(dir), Path::toString);
        watched = new WatchedCorpus(observation, SDL, new RecentChanges(dir));
        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(dir), debounce, onTrigger, watched);
        return observation;
    }

    /**
     * An instant strictly between the event before this call and the event after it: a read that
     * happened after the watcher started and before the file moved.
     *
     * <p>The sleeps are the point rather than a wart. A minute-away instant would sit above every
     * mark and every loss this test produces, so each case would pass on the floor alone and
     * exercise nothing it claims to. Reading the clock inside a gap wide enough to see is what
     * gets an instant between two events microseconds apart.
     */
    private static LocalDateTime between() throws InterruptedException {
        Thread.sleep(5);
        var at = LocalDateTime.now();
        Thread.sleep(5);
        return at;
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
