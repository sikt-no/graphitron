package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.GraphitronModelStore;

import java.nio.file.Path;

/**
 * A fact store for a test to assert against, in the two shapes the store has: private and
 * in-memory, or persisted under a home on disk. The bottom of every fact-store harness in the
 * reactor, and the one place a test opens a store.
 *
 * <p>Named entry points rather than one flagged factory, because the two shapes are not a
 * configuration of each other. An in-memory store dies with the handle and can never be reopened,
 * which is what makes it the right substrate for a case whose subject is rows; a file-backed one
 * survives its handle, reports where it landed, and is what a case about warm start or about a
 * reader on another connection needs. A boolean at the call site would read as a detail rather
 * than as the choice it is.
 *
 * <p>What this level does <em>not</em> do is put rows in the store. That is deliberate, and it is
 * what lets every level above stand on this one: a seeded case fills the store through
 * {@link SeededStore}, and above this module a case whose subject is the generator fills it by
 * running a real capture or a real build through that module's own harness. A store handle that
 * arrived with rows already in it would serve none of them.
 *
 * <p>This harness owns no lifetime. Every entry point hands back a {@link GraphitronModelStore}
 * the caller closes, normally through try-with-resources, so a case that holds two stores at once
 * or reopens one directory is expressible here rather than having to reach past the harness.
 */
public final class FactStores {

    private FactStores() {}

    /**
     * A private in-memory store, empty, with the fact schema created in it. The default: nothing
     * on disk to clean up, nothing shared with a concurrent test, and the whole store gone when
     * the handle closes.
     */
    public static GraphitronModelStore inMemory() {
        return GraphitronModelStore.open();
    }

    /**
     * A store persisted under {@code home}, which the store stamps a compatibility segment onto
     * before keeping its file there. What an in-memory store cannot answer for: that a second open
     * of the same home meets the first one's rows, that the handle reports the directory it landed
     * in, and that a reader mints onto the file rather than onto a guessed one.
     *
     * <p>Hand it a fresh temporary directory per case. The store never deletes what it finds, so a
     * shared home carries a previous case's rows into the next one's assertions.
     */
    public static GraphitronModelStore fileBacked(Path home) {
        return GraphitronModelStore.openAt(home);
    }
}
