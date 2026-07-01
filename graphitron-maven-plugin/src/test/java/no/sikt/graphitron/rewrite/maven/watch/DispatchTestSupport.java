package no.sikt.graphitron.rewrite.maven.watch;

import java.nio.file.Path;
import java.nio.file.WatchEvent;

/**
 * Test-only bridge into {@link SchemaWatcher}'s package-private {@code dispatch(...)}
 * entry point. Lets synthetic-dispatch tests that live outside the {@code watch}
 * package (e.g. dev-goal integration tests in {@code ..maven.dev}) drive events
 * without widening the production surface. Lives under {@code src/test/java} so it
 * cannot be referenced from production code.
 */
public final class DispatchTestSupport {

    private DispatchTestSupport() {}

    public static void dispatch(SchemaWatcher watcher, Path dir, WatchEvent<?> event) {
        watcher.dispatch(dir, event);
    }
}
