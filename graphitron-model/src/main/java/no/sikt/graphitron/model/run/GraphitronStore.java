package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.capture.document.SdlCapture;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Opening a store and filling it, for a caller that owns both.
 *
 * <p>Ownership is the whole of what this offers, and it is binary: a caller opens a store, uses it,
 * and closes it. There is no fallback arm and no demotion, because there is nothing to fall back
 * from. A run that wants no file gets a private in-memory store and never contends with anything; a
 * run that wants a file names one and gets it or fails. Which store a caller has is therefore
 * settled by which method it called, not by an answer it has to interpret afterwards.
 *
 * <p>Capture is a plain call on an open store rather than a thing that hands the store back through
 * a continuation. The gatherers find their own inputs from the configuration, so nothing has to be
 * read before the store is opened and the store's lifetime is the caller's block.
 */
public final class GraphitronStore {

    private GraphitronStore() {}

    /**
     * A private store nothing else can reach, discarded when the caller closes it.
     *
     * <p>What a one-shot run should want. Its facts are this run's, derived from this run's inputs,
     * and a run that shares nothing cannot be delayed by, demoted by or disagree with another.
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
     * <p>One gatherer so far, the SDL reader. The others follow, and each finds its own inputs the
     * same way rather than being handed something a caller read.
     */
    public static void capture(GraphitronModelStore store, GraphIdentity graph,
                               SubjectConfig config) {
        var readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        store.dsl().transaction(tx -> SdlCapture.capture(tx.dsl(), graph, config, readAt));
    }
}
