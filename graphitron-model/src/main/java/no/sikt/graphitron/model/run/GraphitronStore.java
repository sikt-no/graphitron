package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.capture.classpath.ClasspathFactCapture;
import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.capture.jooq.JooqFactCapture;
import no.sikt.graphitron.model.jooq.JooqCatalog;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Opening a store and filling it, for a caller that owns both.
 *
 * <p>Ownership is binary: a caller opens a store, uses it and closes it, and which store it has is
 * settled by which method it called. A run wanting no file gets a private in-memory store that
 * contends with nothing; a run wanting a file names one and gets it or fails.
 *
 * <p>The gatherers find their own inputs from the configuration, so nothing is read before the
 * store is opened and the store's lifetime is the caller's block.
 */
public final class GraphitronStore {

    private GraphitronStore() {}

    /**
     * A private store nothing else can reach, discarded when the caller closes it.
     *
     * <p>What a one-shot run should want: its facts are its own, and a run sharing nothing cannot
     * be delayed by or disagree with another.
     */
    public static GraphitronModelStore inMemory() {
        return GraphitronModelStore.open();
    }

    /**
     * A store on disk at {@code directory}, for a run whose product is the store itself.
     *
     * @throws IllegalStateException if the directory cannot hold a store, which is a failure and
     *         not something to work around: a caller that asked for a file and got memory would
     *         report success and leave nothing behind
     */
    public static GraphitronModelStore at(Path directory) {
        var store = GraphitronModelStore.openAt(directory);
        if (store.location().isEmpty()) {
            store.close();
            throw new IllegalStateException(
                "no store could be opened at " + directory + "; a run that asked for a store on "
                    + "disk cannot answer with one in memory");
        }
        return store;
    }

    /**
     * Fills {@code store} with what {@code graph}'s configured inputs say, in one transaction.
     *
     * <p>Each gatherer finds its own inputs from {@code config}, except the two that read compiled
     * code, which configuration cannot carry: a classpath is assembled by the build tool rather
     * than declared by an author.
     *
     * <p>They want different things of it, so they are told separately. The census reads bytes:
     * {@code classpath} is directories and jars, parsed as classfiles, and nothing is loaded. The
     * catalog cannot be read that way, jOOQ building its tables and keys in static initialisers
     * rather than declaring them in the bytes, so it arrives already built and carries the loader
     * it was built through. No classpath is no census; no catalog is no catalog facts.
     *
     * <p>The census leaves out the jOOQ package, which is the caller's policy and not a rule the
     * census holds: those classes are generated, and a consumer names what a directive resolves
     * against rather than naming them.
     *
     * <p>One transaction, so a run that fails partway leaves the store as it found it.
     */
    public static void capture(GraphitronModelStore store, GraphIdentity graph,
                               SubjectConfig config, List<Path> classpath, JooqCatalog jooq) {
        var readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        store.dsl().transaction(tx -> {
            SdlCapture.capture(tx.dsl(), graph, config, readAt);
            JooqFactCapture.capture(tx.dsl(), jooq, readAt);
            ClasspathFactCapture.capture(tx.dsl(), classpath,
                config.jooqPackage().orElse(null), readAt);
        });
    }
}
