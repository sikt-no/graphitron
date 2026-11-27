package no.sikt.graphitron.model.boot;

import org.jooq.Configuration;
import org.jooq.ExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;

/**
 * One listener every store's writer and readers hand each statement to, where one is installed.
 *
 * <p>It exists for the test-scope census of which views a test run reads, which has to see the
 * statements of stores main code opens; nothing else installs one, and a run without it pays a null
 * check per store opened.
 */
public final class StatementObserver {

    private static volatile ExecuteListener installed;

    private StatementObserver() {}

    /** Installs {@code listener} for every store opened from now on, or none where it is null. */
    public static void install(ExecuteListener listener) {
        installed = listener;
    }

    /** {@code configuration} with the installed listener appended to its own, or as it is. */
    static Configuration observed(Configuration configuration) {
        var listener = installed;
        return listener == null
            ? configuration
            : configuration.deriveAppending(new DefaultExecuteListenerProvider(listener));
    }
}
