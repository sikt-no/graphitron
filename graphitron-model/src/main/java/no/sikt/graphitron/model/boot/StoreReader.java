package no.sikt.graphitron.model.boot;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.function.Function;

/**
 * A second connection onto a store somebody else is writing, for a consumer that answers questions
 * while a build fills the rows: the language server's shape. {@link GraphitronModelStore#reader()}
 * mints it, because the store publishes no URL to reconstruct (the in-memory name carries a private
 * {@link java.util.UUID}) and a reader guessing a path would open a different database than the one
 * the session writes, look fine, and answer from nothing.
 *
 * <p>Its own connection rather than the writer's. The store's {@code DSLContext} is single-threaded
 * by construction and a capture holds it for a whole transaction, so a reader sharing it would
 * either block behind the round or interleave with it; the only reason that has been safe so far is
 * that the MCP server is turn-based, which a language server is not.
 *
 * <p>{@link #read} is the only door, and it is a transaction. A handler assembling an answer from
 * several queries would otherwise see one commit for its first query and the next for its second,
 * reporting a schema that never existed; one transaction per answer makes the consistency claim
 * structural rather than a property of whatever isolation level H2 defaults to, which is why the
 * level is set explicitly at mint time and no bare query surface is exposed beside it. The
 * transaction ends in a rollback: a reader has nothing to commit, so nothing it does can reach the
 * rows the writer owns.
 *
 * <p>A capture is itself one transaction, so the snapshot a read sees is always a whole round.
 * There is no half-written state to defend against and no freshness arm to switch on: the store
 * answers from the last committed capture, which for a save-cadence writer is the last saved
 * content.
 *
 * <p>Reads serialize. One connection cannot carry two transactions, and requests arriving
 * concurrently would otherwise corrupt each other's; serializing them is the honest cost of the
 * single connection, and the remedy if it ever bites is a second reader per thread, which
 * {@link GraphitronModelStore#reader()} mints as readily as the first. Nothing here pools.
 *
 * <p>Closing releases the connection and touches the database no further: an in-memory store's
 * database dies with its owner, a file-backed one is left on disk. A reader outliving its store is
 * a reader onto a closed database, and its next read fails as such rather than answering emptily.
 */
public final class StoreReader implements AutoCloseable {

    /**
     * H2's snapshot level, set per session before any transaction begins. Repeatable read would
     * hold the rows a read already touched; snapshot holds the whole database as of the
     * transaction's start, which is what an answer assembled from several relations needs.
     */
    private static final String ISOLATION =
        "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SNAPSHOT";

    private final Connection connection;
    private final DSLContext dsl;

    StoreReader(Connection connection) {
        this.connection = connection;
        this.dsl = DSL.using(connection, SQLDialect.H2);
        try (Statement statement = connection.createStatement()) {
            statement.execute(ISOLATION);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "the store reader could not set its isolation level: " + e.getMessage(), e);
        }
    }

    /**
     * Runs {@code query} against the store inside one transaction and returns what it produced.
     *
     * <p>The {@link DSLContext} handed to {@code query} is valid for that call only. Holding onto
     * it past the return puts a later query outside the transaction that made its predecessors
     * consistent, which is the failure this method exists to remove.
     *
     * @throws IllegalStateException if the transaction cannot be opened or closed, which means the
     *         database behind this reader is gone rather than that the query was wrong
     */
    public synchronized <T> T read(Function<DSLContext, T> query) {
        Objects.requireNonNull(query, "query");
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "the store reader could not open a read transaction: " + e.getMessage(), e);
        }
        try {
            return query.apply(dsl);
        } finally {
            rollback();
        }
    }

    /**
     * Ends the read transaction. A failure here is swallowed deliberately: there is nothing of
     * record to lose, and the caller is either receiving an answer already assembled or handling
     * the query's own failure, neither of which this improves by throwing a second exception.
     */
    private void rollback() {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            // The next read opens its own transaction and fails there if the connection is gone.
        }
    }

    /** Releases the connection. The database itself is the store's to close, never a reader's. */
    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // Nothing survives the connection, so a failure closing it has no consequence.
        }
    }
}
