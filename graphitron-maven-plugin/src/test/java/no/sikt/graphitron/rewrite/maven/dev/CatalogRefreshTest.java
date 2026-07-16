package no.sikt.graphitron.rewrite.maven.dev;

import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.maven.watch.DebounceExecutor;
import no.sikt.graphitron.rewrite.maven.watch.DispatchTestSupport;
import no.sikt.graphitron.rewrite.maven.watch.SchemaWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Map;
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

    @AfterEach
    void tearDown() throws Exception {
        if (watcher != null) watcher.close();
        if (debounce != null) debounce.close();
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
                null,
                List.of(),
                List.of()
            )),
            List.of(),
            List.of()
        );

        Runnable rebuilder = () -> {
            workspace.setBuildOutput(
                new GraphQLRewriteGenerator.BuildArtifacts(
                    newCatalog,
                    new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of())),
                ValidationReport.empty());
            fired.countDown();
        };

        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(classesDir), debounce, rebuilder, ".class");

        DispatchTestSupport.dispatch(watcher, classesDir, entryCreateEvent(Path.of("Tables.class")));

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

        DispatchTestSupport.dispatch(watcher, classesDir, entryModifyEvent(Path.of("schema.graphqls")));

        Thread.sleep(WAIT_MS);
        assertThat(rebuilds.get())
            .as(".graphqls write under a .class watcher must not fire")
            .isZero();
    }

    @Test
    void javaSourceWriteRefreshesSourceIndexWithoutCatalogRebuild(@TempDir Path srcDir) throws Exception {
        // Source cadence: a .java edit refreshes the LSP-owned source
        // position index, decoupled from the .class catalog rebuild. The
        // workspace's catalog must stay untouched (no buildOutput swap), proving
        // positions ride the source cadence rather than the generator build.
        var workspace = new Workspace(CompletionData.empty());
        assertThat(workspace.sourceIndex().isEmpty()).isTrue();

        Path javaFile = srcDir.resolve("com/example/PriceService.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
            package com.example;
            public class PriceService {
                public Object price() { return null; }
            }
            """);

        var fired = new CountDownLatch(1);
        Runnable refresher = () -> {
            // Real production path: the workspace owns the walker and the index.
            workspace.refreshSourceIndex(List.of(srcDir));
            fired.countDown();
        };

        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(srcDir), debounce, refresher, ".java");

        DispatchTestSupport.dispatch(watcher, javaFile.getParent(),
            entryModifyEvent(Path.of("PriceService.java")));

        assertThat(fired.await(WAIT_MS, TimeUnit.MILLISECONDS))
            .as("source refresher must fire on .java write")
            .isTrue();

        // Position is observable through the workspace's volatile source index,
        // without any catalog rebuild having run.
        assertThat(workspace.sourceIndex().classes())
            .containsKey("com.example.PriceService");
        assertThat(workspace.catalog().tables()).isEmpty();
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
