package no.sikt.graphitron.rewrite.maven.dev;

import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.maven.watch.DebounceExecutor;
import no.sikt.graphitron.rewrite.maven.watch.SchemaWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the dev goal's {@code .class}-watcher → catalog-rebuilder →
 * workspace-swap chain end-to-end (minus the rewrite-generator step,
 * which {@code CatalogBuilderTest} covers in isolation). Validates that
 * a single {@code .class} write triggers the suffix-filtered watcher
 * and that the resulting catalog is observable through {@link Workspace}.
 */
class CatalogRefreshTest {

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
    void classFileWriteRefreshesWorkspaceCatalog(@TempDir Path classesDir) throws Exception {
        var workspace = new Workspace(CompletionData.empty());
        assertThat(workspace.catalog().tables()).isEmpty();

        var fired = new CountDownLatch(1);
        var newCatalog = new CompletionData(
            List.of(new CompletionData.Table(
                "FILM",
                "Movies the rental store carries",
                CompletionData.SourceLocation.UNKNOWN,
                List.of(),
                List.of()
            )),
            List.of(),
            List.of()
        );

        Runnable rebuilder = () -> {
            workspace.setCatalog(newCatalog);
            fired.countDown();
        };

        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(classesDir), debounce, rebuilder, ".class");
        watcherThread = new Thread(watcher::run, "test-classpath-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();

        Files.writeString(classesDir.resolve("Tables.class"), "stand-in for a real .class file");

        assertThat(fired.await(WAIT_MS, TimeUnit.MILLISECONDS))
            .as("rebuilder must fire on .class write")
            .isTrue();

        // The swap is observable through the workspace's volatile catalog
        // ref without taking the file lock.
        assertThat(workspace.catalog().tables())
            .extracting(CompletionData.Table::name)
            .containsExactly("FILM");
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
            rebuilds::incrementAndGet, ".class");
        watcherThread = new Thread(watcher::run, "test-classpath-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();

        Files.writeString(classesDir.resolve("schema.graphqls"), "type Q { x: Int }");

        Thread.sleep(WAIT_MS);
        assertThat(rebuilds.get())
            .as(".graphqls write under a .class watcher must not fire")
            .isZero();
    }
}
