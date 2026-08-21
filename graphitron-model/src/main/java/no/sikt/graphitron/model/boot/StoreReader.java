package no.sikt.graphitron.model.boot;

import org.h2.jdbc.JdbcException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A second connection onto a store somebody else is writing, for a consumer that answers questions
 * while a build fills the rows: the language server's shape.
 * {@link GraphitronModelStore#reader(ReadBudget)} mints it, because the store publishes no URL to
 * reconstruct (the in-memory name carries a private {@link java.util.UUID}) and a reader guessing a
 * path would open a different database than the one the session writes, look fine, and answer from
 * nothing.
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
 * {@link GraphitronModelStore#reader(ReadBudget)} mints as readily as the first. Nothing here pools.
 *
 * <p>Every reader states a {@link ReadBudget}, and it is a property of the session rather than of
 * each call. Serialized reads are exactly what makes an unbounded statement more than its own
 * caller's problem: one query that never returns is head-of-line blocking every request queued
 * behind it. The budget is what turns that into one request losing one answer. A consumer whose
 * grains want different budgets mints a reader per grain rather than bracketing each read, which
 * costs no statements and stops the two grains serializing behind each other besides.
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

    /**
     * H2's vendor code for a statement the database cancelled, which is what an expired
     * {@code QUERY_TIMEOUT} raises. Keyed on the code rather than on the exception type because a
     * <em>lock</em> timeout is the same {@link java.sql.SQLTimeoutException} with a different code
     * (50200), wants the opposite remedy, and belongs to the write path.
     */
    private static final int STATEMENT_CANCELLED = 57014;

    private final Connection connection;
    private final DSLContext dsl;
    private final ReadBudget budget;

    StoreReader(Connection connection, ReadBudget budget) {
        this.connection = connection;
        this.dsl = DSL.using(connection, SQLDialect.H2);
        this.budget = Objects.requireNonNull(budget, "budget");
        Optional<String> command = budget.sessionCommand();
        try (Statement statement = connection.createStatement()) {
            statement.execute(ISOLATION);
            if (command.isPresent()) {
                statement.execute(command.get());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "the store reader could not prepare its session: " + e.getMessage(), e);
        }
    }

    /** The budget every statement this reader issues is held to. */
    public ReadBudget budget() {
        return budget;
    }

    /**
     * Runs {@code query} against the store inside one transaction and returns what it produced, or
     * {@link StoreAnswer.OutOfBudget} where a statement overran this reader's {@link ReadBudget}.
     *
     * <p>The {@link DSLContext} handed to {@code query} is valid for that call only. Holding onto
     * it past the return puts a later query outside the transaction that made its predecessors
     * consistent, which is the failure this method exists to remove.
     *
     * <p>Only an expired budget becomes an arm. Every other failure propagates unchanged, because a
     * query the database refused is a defect in that query and an arm that swallowed it would turn
     * a build-breaking bug into a warning nobody reads. The transaction is rolled back on both
     * paths, which leaves the session usable for the next read: an aborted statement is the
     * statement's end, not the connection's.
     *
     * @throws IllegalStateException if the transaction cannot be opened, which means the database
     *         behind this reader is gone rather than that the query was wrong
     */
    public synchronized <T> StoreAnswer<T> read(Function<DSLContext, T> query) {
        Objects.requireNonNull(query, "query");
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "the store reader could not open a read transaction: " + e.getMessage(), e);
        }
        try {
            return new StoreAnswer.Answered<>(query.apply(dsl));
        } catch (DataAccessException e) {
            if (!ranOutOfBudget(e)) {
                throw e;
            }
            return new StoreAnswer.OutOfBudget<>(statementOf(e), budget);
        } finally {
            rollback();
        }
    }

    /**
     * Whether {@code failure} carries an aborted statement anywhere in its cause chain. jOOQ wraps
     * the driver's exception, so the shape that survives is the vendor code on the {@link SQLException}
     * underneath rather than anything about the wrapper.
     *
     * <p>Not {@code FactCapture}'s lock-timeout predicate, and not merely because
     * {@code graphitron-model} declares no dependency on the module that holds it. That one keys on
     * {@link java.sql.SQLTimeoutException} in general, deliberately, so that it catches a writer out
     * of lock budget while letting a deadlock keep its retry. An expired statement is the same type
     * with an opposite remedy, so one predicate cannot answer both questions.
     */
    static boolean ranOutOfBudget(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == STATEMENT_CANCELLED) {
                return true;
            }
        }
        return false;
    }

    /**
     * The statement the database killed, as H2 recorded it on the exception. Falls back to the
     * wrapper's own message where the chain carries no statement text, a warning naming something
     * being the point rather than naming SQL specifically.
     */
    private static String statementOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JdbcException jdbc && jdbc.getSQL() != null) {
                return jdbc.getSQL();
            }
        }
        return String.valueOf(failure.getMessage());
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
