package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.StoreUnavailableException;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import org.jooq.exception.DataAccessException;

import java.nio.file.Path;
import java.sql.SQLTimeoutException;
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
     * <p>Nothing to check on the way out any more. This used to open and then ask whether the
     * answer was a file, because the open would hand back a private in-memory store rather than
     * refuse; a caller that asked for a file and got memory would report success and leave nothing
     * behind, so this caught it. The open says so itself now.
     *
     * @throws StoreUnavailableException if the store cannot be opened
     */
    public static GraphitronModelStore at(Path directory) {
        return GraphitronModelStore.openAt(directory);
    }

    /**
     * Fills {@code store} with what {@code graph}'s inputs say, in one transaction, for a caller
     * whose whole business with the store is this.
     *
     * <p>What each gatherer reads, and why the two compiled-code inputs are separate, is
     * {@link ModelCapture}'s. This adds the transaction and the instant: a run that fails partway
     * leaves the store as it found it, and every relation dates the same reading.
     */
    public static void capture(GraphitronModelStore store, GraphIdentity graph,
                               SubjectConfig config, List<ClasspathEntry> classpath,
                               JooqCatalog jooq) {
        var readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        capture(graph.name(), () -> store.dsl().transaction(tx ->
            ModelCapture.capture(tx.dsl(), graph, config, classpath, jooq, readAt)));
    }

    /**
     * Runs {@code body} as a capture of {@code graphName}, saying in the person's words when it
     * could not take a lock.
     *
     * <p>Here rather than at one caller, because it is a rule about capturing into a store and not
     * about any one way of doing it. It lived at one of three call paths for a while and the other
     * two handed back the driver's own exception, which put the good message on the one-shot goal
     * and the bad one on the session that recaptures, exactly the wrong way round: a long-lived
     * store is the one with something to contend with.
     *
     * <p>A contended lock is the one write failure a person can act on. Everything else keeps the
     * driver's words, there being nothing to advise.
     */
    public static void capture(String graphName, Runnable body) {
        try {
            body.run();
        } catch (DataAccessException failure) {
            if (!contendedLock(failure)) {
                throw failure;
            }
            throw new StoreUnavailableException(("graphitron: could not take a lock on the fact "
                + "store while capturing graph '%s'. Another graphitron process is writing the "
                + "same store; let it finish and run again.").formatted(graphName), failure);
        }
    }

    /**
     * Whether {@code failure} is a writer that could not take a lock, anywhere in its cause chain.
     * jOOQ wraps the driver's exception and H2 wraps its own store's, so the shape that survives
     * both is the JDBC contract: a lock timeout arrives as a {@link SQLTimeoutException} (H2 raises
     * error 50200, SQL state {@code HYT00}). Keying on that rather than on a message or a vendor
     * code also keeps a deadlock out, which arrives as a
     * {@link java.sql.SQLTransactionRollbackException} and is a different thing to say.
     *
     * <p>The message names no particular row, deliberately. Nothing waits for a lock now, so any
     * row can be the one that refused, and a message naming the anchor would be a confident wrong
     * answer on a store where a clear met a held row instead.
     *
     * <p>That the type is enough holds only while no writer session carries a statement budget. An
     * expired {@link no.sikt.graphitron.model.boot.ReadBudget} raises the same
     * {@link SQLTimeoutException} with vendor code 57014, and this predicate would read it as lock
     * contention and blame a query that was merely too slow. The read side therefore keys on the
     * vendor code rather than the type; whoever gives a writer a budget has to do the same here.
     */
    public static boolean contendedLock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
