package no.sikt.graphitron.rewrite.maven.dev;

import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.capture.JavaSourceFacts;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the dev goal's {@code .class}-watcher → catalog-rebuilder →
 * workspace-swap chain end-to-end (minus the rewrite-generator step,
 * which {@code CatalogBuilderTest} covers in isolation). Validates that
 * a single {@code .class} write triggers the suffix-filtered watcher
 * and that the resulting build output is observable through {@link Workspace}.
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
    void classFileWriteRefreshesTheWorkspaceBuildOutput(@TempDir Path classesDir) throws Exception {
        var workspace = new Workspace();
        assertThat(workspace.snapshot()).isInstanceOf(LspSchemaSnapshot.Unavailable.class);

        var fired = new CountDownLatch(1);
        // A directive only this round's snapshot declares, so the assertion below distinguishes
        // the swapped output from the pre-build state rather than merely from nothing.
        var rebuilt = new LspSchemaSnapshot.Built.Current(
            List.of(new DirectiveShape("auth", List.of(), Optional.empty())));

        Runnable rebuilder = () -> {
            workspace.setBuildOutput(
                new GraphQLRewriteGenerator.BuildArtifacts(CompletionData.empty(), rebuilt),
                ValidationReport.empty());
            fired.countDown();
        };

        debounce = new DebounceExecutor(DEBOUNCE_MS);
        watcher = new SchemaWatcher(Set.of(classesDir), debounce, rebuilder, ".class");

        DispatchTestSupport.dispatch(watcher, classesDir, entryCreateEvent(Path.of("Tables.class")));

        assertThat(fired.await(WAIT_MS, TimeUnit.MILLISECONDS))
            .as("rebuilder must fire on .class write")
            .isTrue();

        // The swap is observable through the workspace's volatile snapshot
        // ref without taking the file lock.
        assertThat(workspace.snapshot()).isSameAs(rebuilt);
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
    void javaSourceWriteMovesTheStoreRowWithoutAGeneratorPass(@TempDir Path srcDir) throws Exception {
        // Source cadence, at the store layer: a .java edit writes the java_ family and the index
        // projection beside it, with no generator round in between. The workspace's build output
        // must stay untouched (no buildOutput swap), which is what makes the pin about the cadence
        // rather than about a build having happened to run.
        var workspace = new Workspace();
        assertThat(workspace.sourceIndex().isEmpty()).isTrue();

        Path javaFile = srcDir.resolve("com/example/PriceService.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
            package com.example;
            public class PriceService {
                public Object price() { return null; }
            }
            """);

        try (var store = GraphitronModelStore.open()) {
            var facts = new JavaSourceFacts(store.dsl());
            var walker = new SourceWalker();
            var fired = new CountDownLatch(1);
            Runnable refresher = () -> {
                // The production path: one walk, the store and the index off it.
                var walk = walker.walkFiles(List.of(srcDir));
                facts.refresh(List.of(srcDir), walk);
                workspace.setSourceIndex(SourceWalker.indexOf(walk));
                fired.countDown();
            };

            debounce = new DebounceExecutor(DEBOUNCE_MS);
            watcher = new SchemaWatcher(Set.of(srcDir), debounce, refresher, ".java");

            DispatchTestSupport.dispatch(watcher, javaFile.getParent(),
                entryModifyEvent(Path.of("PriceService.java")));

            assertThat(fired.await(WAIT_MS, TimeUnit.MILLISECONDS))
                .as("source refresher must fire on .java write")
                .isTrue();

            assertThat(store.dsl().select(JAVA_CLASS_DECLARATION.CLASS_NAME)
                .from(JAVA_CLASS_DECLARATION).fetch(0, String.class))
                .as("the declaration is a store row on the source cadence")
                .containsExactly("com.example.PriceService");
            assertThat(workspace.sourceIndex().classes())
                .containsKey("com.example.PriceService");
            assertThat(workspace.snapshot()).isInstanceOf(LspSchemaSnapshot.Unavailable.class);
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
