package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.capture.jooq.JooqFactCapture;
import no.sikt.graphitron.model.jooq.JooqCatalog;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

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
     * <p>Each gatherer finds its own inputs from {@code config}. The one thing configuration cannot
     * carry is {@code codegen}: the consumer's generated jOOQ classes are resolved reflectively, and
     * only the build tool can assemble the classpath they live on. A configuration naming no jOOQ
     * package captures no catalog facts and never touches the loader.
     *
     * <p>One transaction, so a run that fails partway leaves the store as it found it.
     */
    public static void capture(GraphitronModelStore store, GraphIdentity graph,
                               SubjectConfig config, ClassLoader codegen) {
        var readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        var jooq = config.jooqPackage()
            .map(jooqPackage -> new JooqCatalog(jooqPackage, codegen))
            .orElse(null);
        store.dsl().transaction(tx -> {
            SdlCapture.capture(tx.dsl(), graph, config, readAt);
            JooqFactCapture.capture(tx.dsl(), jooq, readAt);
        });
    }
}
