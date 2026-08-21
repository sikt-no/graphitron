package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p>It counts what it opens, and that count is the only instrument that sees every boot in a run.
 * A schema boot is the most expensive thing a fact-store test can do, so this module runs almost
 * every case on a store {@link ThreadConfinedStore} booted once per thread and clears between
 * bodies; {@link #boots()} is what notices a path that boots per case appearing beside it. The
 * count carries no policy of its own, because the harness is reached from four modules and what
 * counts as too many boots is each one's own claim: the module that owns a funnel states its
 * budget next to the funnel.
 */
public final class FactStores {

    private static final AtomicLong BOOTS = new AtomicLong();

    private FactStores() {}

    /**
     * A private in-memory store, empty, with the fact schema created in it. The default: nothing
     * on disk to clean up, nothing shared with a concurrent test, and the whole store gone when
     * the handle closes.
     */
    public static GraphitronModelStore inMemory() {
        BOOTS.incrementAndGet();
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
        BOOTS.incrementAndGet();
        return GraphitronModelStore.openAt(home);
    }

    /**
     * How many stores this harness has opened so far in this JVM, across every thread and every
     * entry point. Monotonic, so a reader that wants a run's total reads it at the end of the run;
     * a module holding itself to a budget checks it where its own cases pass, which is what
     * {@link ThreadConfinedStore} does.
     */
    public static long boots() {
        return BOOTS.get();
    }

    /**
     * One in-memory store for a whole test class, as a JUnit extension.
     *
     * <p>The third lifetime, beside the two above, and it is a lifetime rather than a convenience:
     * a class whose cases each open their own store pays the fact schema's two thousand statements
     * per case, which is what makes a case-per-claim test class expensive enough that an author
     * starts merging claims to keep it fast. Declared as an extension rather than as a
     * {@code @BeforeAll} field so no test spells the store type, and so the close cannot be
     * forgotten:
     *
     * <pre>{@code
     * @RegisterExtension
     * static final FactStores.ClassStore STORE = FactStores.perClass();
     *
     * @Test void aClaim() {
     *     var store = STORE.handle();
     *     ...
     * }
     * }</pre>
     *
     * <p>Not the per-thread funnel, and the reason is the surface rather than the lifetime:
     * {@link SeededStore#withSeededStore(java.util.function.Consumer)} hands a body a
     * {@link org.jooq.DSLContext}, deliberately, and a case that needs the store <em>handle</em>
     * (the connection, or something the handle mints) has nothing to ask it for. Widening the funnel
     * to hand out the handle would give every case on it the ability to close the thread's store.
     * These boots are counted in {@link #boots()} like any other and land under the funnel's budget.
     *
     * <p>Only for a class whose cases do not interfere. The store arrives empty and stays whatever
     * the cases leave it, so a class that seeds rows and counts them wants a store per case, from
     * {@link #inMemory()}, or the funnel, which clears between bodies.
     */
    public static ClassStore perClass() {
        return new ClassStore();
    }

    /**
     * The handle behind {@link #perClass()}. Owns the store's lifetime, opening it before the
     * class's first case and closing it after the last, so a case borrows the handle and never
     * closes it.
     */
    public static final class ClassStore implements BeforeAllCallback, AfterAllCallback {

        private GraphitronModelStore store;

        private ClassStore() {}

        /** The store, open for the duration of the class. Never closed by a caller. */
        public GraphitronModelStore handle() {
            if (store == null) {
                throw new IllegalStateException(
                    "the class store is not open; declare it @RegisterExtension on a static field");
            }
            return store;
        }

        @Override
        public void beforeAll(ExtensionContext context) {
            store = inMemory();
        }

        @Override
        public void afterAll(ExtensionContext context) {
            if (store != null) {
                store.close();
                store = null;
            }
        }
    }
}
