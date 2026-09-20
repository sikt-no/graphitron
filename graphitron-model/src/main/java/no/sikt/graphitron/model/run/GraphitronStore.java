package no.sikt.graphitron.model.run;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import no.sikt.graphitron.model.config.RunContext;
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
     * A store for {@code ctx}, captured and handed back open.
     *
     * <p>Every argument the capture needs is already the run's: the graph it writes under, the
     * corpus it reads, the classpath it scans and the catalog it resolves against are all stated
     * once in the context and derived the same way by everyone who captures. Deriving them here
     * rather than at each caller is what keeps two callers from deriving them differently.
     *
     * <p>In memory where the context names no store home, which is what a run that wants facts and
     * keeps none asks for.
     *
     * @throws StoreUnavailableException if the store cannot be opened
     */
    public static GraphitronModelStore captured(RunContext ctx) {
        var graph = new GraphIdentity(ctx.graphName(), ctx.basedir());
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        return ctx.storeDirectory() == null
            ? capturedInMemory(graph, SubjectConfig.of(ctx), ctx.classpathRoots(), jooq)
            : captured(ctx.storeDirectory(), graph, SubjectConfig.of(ctx), ctx.classpathRoots(),
                jooq);
    }

    /**
     * Refuses a store that already belongs to a different checkout of the same graph name.
     *
     * <p>Asked at the open, because that is where every owner passes and there is nothing useful
     * to do about it later. Two modules claiming one graph name would otherwise write over each
     * other's partition, each reading the other's rows as its own; the run stops and says which
     * two directories are in dispute and what to set.
     *
     * <p>Asked of the store's rows rather than of how the open went. A store nobody has written
     * records no graph, so the lookup answers nothing and this returns; there is no separate
     * question of whether the file pre-existed, and a flag saying so would be a second answer to
     * one the rows already give.
     *
     * <p>It throws rather than recovering. A store that cannot be had is the caller's to handle,
     * and the caller is a mojo, which knows how to fail a build and what to tell the person
     * reading the log.
     */
    private static void refuseIfOwnedElsewhere(GraphitronModelStore store, GraphIdentity graph) {
        String recorded = store.dsl().select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
            .where(STORE_GRAPH.GRAPH_NAME.eq(graph.name()))
            .fetchOne(0, String.class);
        if (recorded == null || recorded.equals(graph.baseDir().toString())) {
            return;
        }
        throw new StoreUnavailableException(("graphitron: graph '%s' is already recorded in the "
            + "fact store for %s, but this run's base directory is %s. Set <graphName> so the two "
            + "modules stop claiming one name, then run again.")
            .formatted(graph.name(), recorded, graph.baseDir()));
    }

    /**
     * A store at {@code directory} with {@code graph} captured into it, handed back open.
     *
     * <p>The two calls a run makes, as the one call it makes them in: opening a store and filling
     * it are the same act from outside, and a caller that wants facts to read wants both. It hands
     * the store back rather than closing it, because reading is what the caller opened it for and
     * the reads happen after.
     *
     * <p>Whoever calls this owns the store. That is a mojo or a test and never a pass: a pass is
     * handed a {@link no.sikt.graphitron.model.read.StoreHandle} over somebody else's store, so it
     * has no home to name and nothing to open.
     *
     * @throws StoreUnavailableException if the store cannot be opened
     */
    public static GraphitronModelStore captured(Path directory, GraphIdentity graph,
                                                SubjectConfig config,
                                                List<ClasspathEntry> classpath, JooqCatalog jooq) {
        if (directory == null) {
            // No home to name is a private store that dies with the run, which is what a caller
            // with nowhere to keep facts is asking for.
            return capturedInMemory(graph, config, classpath, jooq);
        }
        var store = at(directory);
        try {
            refuseIfOwnedElsewhere(store, graph);
            capture(store, graph, config, classpath, jooq);
        } catch (RuntimeException | Error failure) {
            store.close();
            throw failure;
        }
        return store;
    }

    /** {@link #captured} into a store that lives as long as the caller, for a run with no home. */
    public static GraphitronModelStore capturedInMemory(GraphIdentity graph, SubjectConfig config,
                                                        List<ClasspathEntry> classpath,
                                                        JooqCatalog jooq) {
        var store = inMemory();
        try {
            capture(store, graph, config, classpath, jooq);
        } catch (RuntimeException | Error failure) {
            store.close();
            throw failure;
        }
        return store;
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
