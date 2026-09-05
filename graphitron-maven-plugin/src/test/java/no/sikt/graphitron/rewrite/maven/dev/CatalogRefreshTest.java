package no.sikt.graphitron.rewrite.maven.dev;

import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.maven.watch.DebounceExecutor;
import no.sikt.graphitron.rewrite.maven.watch.DispatchTestSupport;
import no.sikt.graphitron.rewrite.maven.watch.SchemaWatcher;
import no.sikt.graphitron.rewrite.maven.watch.WatchedCorpus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static no.sikt.graphitron.model.test.FactWriters.refreshJavaSources;
import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the dev goal's {@code .class}-watcher → catalog-rebuilder →
 * workspace-swap chain end-to-end (minus the rewrite-generator step,
 * which {@code CatalogBuilderTest} covers in isolation). Validates that
 * a single {@code .class} write triggers the suffix-filtered watcher
 * and that the resulting round reaches the {@link Workspace}.
 */
class CatalogRefreshTest {

    private static final long DEBOUNCE_MS = 100;

    /**
     * How long a positive test waits before concluding the trigger never fired, in milliseconds.
     * The awaited window holds the watcher's dispatch, the {@link DebounceExecutor} delay, and
     * whatever the trigger itself does, which in
     * {@link #javaSourceWriteMovesTheStoreRowWithoutAGeneratorPass} is real work:
     * {@link no.sikt.graphitron.model.test.FactWriters#refreshJavaSources} is a
     * {@link no.sikt.graphitron.model.sources.SourceWalker} parse through the Compiler Tree API,
     * cold on its first use in the surefire JVM, plus the jOOQ writes that land the walk in the
     * store.
     *
     * <p>What that refresh costs, measured under the conditions this figure has to survive rather
     * than on a quiet machine. A {@code System.nanoTime} bracket around the call, with this
     * module's suite running its classes four-way concurrent on a fourteen-core machine held at
     * load average seventeen to thirty-four, put sixteen samples between 483 ms and 4,308 ms. The
     * spread is the finding: the refresh parses one small source file and writes a handful of rows,
     * so its own work is milliseconds and almost everything the clock sees is scheduling delay
     * under contention. Sized against the worst of that spread rather than its floor, this ceiling
     * leaves about fourteen times the room the refresh took.
     *
     * <p>Generous on purpose, and the direction is what makes that safe.
     * {@link java.util.concurrent.CountDownLatch#await} returns the moment the latch counts down,
     * so a green run never pays this figure at all; it is spent only on a run that was going to
     * fail anyway. That is also why the near-free rebuilder in
     * {@link #classFileWriteReachesTheWorkspace} takes the same ceiling: an await budget is a bound
     * on failure, not an estimate of the work, and nothing holds that rebuilder cheap.
     *
     * <p>So reaching it means the refresher is broken or hung, not slow. A machine that stretched a
     * sub-second unit of work past a full minute has failed at something larger than this test, and
     * a merely slow refresh has an order of magnitude to travel first.
     *
     * <p>It was the same figure as {@link #QUIESCENCE_MS}, 1,600 ms serving both, and that figure
     * was never a ceiling: it falls inside the spread measured above, under five of those sixteen
     * samples, so it was a coin toss with machine load rather than a fact about the code. That is
     * the race this module loses on a cold first build, its test classes running four-way
     * concurrent while {@link no.sikt.graphitron.rewrite.maven.DevMojoTest}'s real generator work
     * competes for the same cores.
     */
    private static final long FIRE_CEILING_MS = DEBOUNCE_MS + 60_000;

    /**
     * How long {@link #graphqlsWriteDoesNotFireClasspathWatcher} watches a watcher that must not
     * fire, in milliseconds, before it is satisfied that nothing did. The window prices dispatch
     * plus the {@link DebounceExecutor} delay for a fire that should never arrive, so its floor is
     * a small multiple of {@link #DEBOUNCE_MS}; this is sixteen times that.
     *
     * <p>The opposite axis from {@link #FIRE_CEILING_MS}, which is why the two are separate
     * constants rather than one figure serving both. {@link Thread#sleep} pays this in full on
     * every green run, so a larger figure is strictly slower and a shorter one strictly less
     * sensitive: under the same load that makes a late fire possible, a mis-wired watcher's fire
     * could land after a short window had closed, and a loud failure would come back as a silent
     * pass. The 1.6 s per run buys that sensitivity.
     */
    private static final long QUIESCENCE_MS = DEBOUNCE_MS + 1500;

    private DebounceExecutor debounce;
    private SchemaWatcher watcher;

    @AfterEach
    void tearDown() throws Exception {
        if (watcher != null) watcher.close();
        if (debounce != null) debounce.close();
    }

    @Test
    void classFileWriteReachesTheWorkspace(@TempDir Path classesDir) throws Exception {
        var workspace = new Workspace();
        // One open file, its open-time enqueue already drained, so what the queue holds afterwards
        // is what the rebuild put there. The queue is the whole of what a round does to the
        // workspace now: every surface reads the store, so a round changes what they answer by
        // changing what the store holds, and this says the workspace was told.
        workspace.didOpen("file:///a.graphqls", 1, "type A { x: Int }\n");
        workspace.drainRecalculate();

        var fired = new CountDownLatch(1);
        Runnable rebuilder = () -> {
            workspace.markAllForRecalculation();
            fired.countDown();
        };

        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(classesDir), debounce, rebuilder, ".class",
            WatchedCorpus.unobserved("classpath"));

        DispatchTestSupport.dispatch(watcher, classesDir, entryCreateEvent(Path.of("Tables.class")));

        assertThat(fired.await(FIRE_CEILING_MS, TimeUnit.MILLISECONDS))
            .as("rebuilder must fire on .class write")
            .isTrue();

        assertThat(workspace.drainRecalculate()).containsExactly("file:///a.graphqls");
    }

    @Test
    void graphqlsWriteDoesNotFireClasspathWatcher(@TempDir Path classesDir) throws Exception {
        // Suffix mismatch: a .graphqls write under a watcher configured
        // for .class must not trigger the rebuilder. Otherwise the dev
        // goal would double-rebuild on every schema save (once via the
        // schema watcher, once via the classpath watcher).
        var rebuilds = new java.util.concurrent.atomic.AtomicInteger();

        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(classesDir), debounce,
            rebuilds::incrementAndGet, ".class", WatchedCorpus.unobserved("classpath"));

        DispatchTestSupport.dispatch(watcher, classesDir, entryModifyEvent(Path.of("schema.graphqls")));

        Thread.sleep(QUIESCENCE_MS);
        assertThat(rebuilds.get())
            .as(".graphqls write under a .class watcher must not fire")
            .isZero();
    }

    @Test
    void javaSourceWriteMovesTheStoreRowWithoutAGeneratorPass(@TempDir Path srcDir) throws Exception {
        // Source cadence, at the store layer: a .java edit writes the java_ family, with no
        // generator round in between. An open file whose queue entry has been drained is what says
        // so: a round enqueues every open file, so an empty queue afterwards is the pin about the
        // cadence rather than about a build having happened to run.
        var workspace = new Workspace();
        workspace.didOpen("file:///a.graphqls", 1, "type A { x: Int }\n");
        workspace.drainRecalculate();

        Path javaFile = srcDir.resolve("com/example/PriceService.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
            package com.example;
            public class PriceService {
                public Object price() { return null; }
            }
            """);

        try (var store = FactStores.inMemory()) {
            var fired = new CountDownLatch(1);
            Runnable refresher = () -> {
                // The production path: one walk, one sink.
                refreshJavaSources(store.dsl(), List.of(srcDir));
                fired.countDown();
            };

            debounce = new DebounceExecutor(DEBOUNCE_MS);
            watcher = new SchemaWatcher(Set.of(srcDir), debounce, refresher, ".java",
                WatchedCorpus.unobserved("java-source"));

            DispatchTestSupport.dispatch(watcher, javaFile.getParent(),
                entryModifyEvent(Path.of("PriceService.java")));

            assertThat(fired.await(FIRE_CEILING_MS, TimeUnit.MILLISECONDS))
                .as("source refresher must fire on .java write")
                .isTrue();

            assertThat(store.dsl().select(JAVA_CLASS_DECLARATION.CLASS_NAME)
                .from(JAVA_CLASS_DECLARATION).fetch(0, String.class))
                .as("the declaration is a store row on the source cadence")
                .containsExactly("com.example.PriceService");
            assertThat(workspace.drainRecalculate())
                .as("a source-cadence refresh must not run a generator pass")
                .isEmpty();
        }
    }

    private static WatchEvent<?> entryCreateEvent(Path relative) {
        return new WatchEvent<Path>() {
            @Override public Kind<Path> kind() { return StandardWatchEventKinds.ENTRY_CREATE; }
            @Override public int count() { return 1; }
            @Override public Path context() { return relative; }
        };
    }

    private static WatchEvent<?> entryModifyEvent(Path relative) {
        return new WatchEvent<Path>() {
            @Override public Kind<Path> kind() { return StandardWatchEventKinds.ENTRY_MODIFY; }
            @Override public int count() { return 1; }
            @Override public Path context() { return relative; }
        };
    }
}
