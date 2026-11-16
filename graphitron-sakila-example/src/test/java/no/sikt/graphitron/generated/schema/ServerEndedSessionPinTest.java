package no.sikt.graphitron.generated.schema;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * What pgjdbc raises on the statement after PostgreSQL ended a session, pinned against the real
 * server, and what {@link OwnedConnections#endedOverTransaction} makes of it. A mutation field's
 * failure messages are written on a fresh connection only when an exception settle holds names the
 * transaction as why the session ended, so this is what decides whether a session the server ended
 * over its transaction writes the message or drops it; the execution tests are built on these pins,
 * not on the state the server sends.
 *
 * <p>Each case ends one session it opened itself: an idle transaction left past
 * {@code idle_in_transaction_session_timeout}, a transaction left past {@code transaction_timeout}
 * (PostgreSQL 17 and later), and a backend terminated from another session. Where a case goes on
 * to ask, it also pins that the driver then reports the connection no longer valid, which is what
 * {@code PinnedConnection.release} reads to evict such a connection without failing the operation.
 */
@UnitTier
class ServerEndedSessionPinTest {

    private static Connection open() throws SQLException {
        return SakilaDatabase.connect(SakilaDatabase.url(), SakilaDatabase.user(), SakilaDatabase.password());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** A connection in an open transaction that has run one statement, as a mutation field's is at settle. */
    private static Connection inTransaction(String setting) throws SQLException {
        var connection = open();
        if (setting != null) {
            execute(connection, setting);
        }
        connection.setAutoCommit(false);
        execute(connection, "select 1");
        return connection;
    }

    private static SQLException failureOf(ThrowingRunnable call) {
        try {
            call.run();
        } catch (SQLException e) {
            return e;
        }
        throw new AssertionError("the call succeeded on a session the server ended");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws SQLException;
    }

    @Test
    void idleInTransactionTimeout_theRollbackRaises25P03_whichNamesTheTransaction() throws Exception {
        try (var connection = inTransaction("set idle_in_transaction_session_timeout = 200")) {
            Thread.sleep(1_000);
            var raised = failureOf(connection::rollback);

            assertThat(raised.getSQLState()).isEqualTo("25P03");
            assertThat(OwnedConnections.endedOverTransaction(raised, SQLDialect.POSTGRES)).isTrue();
        }
    }

    @Test
    void idleInTransactionTimeout_theCommitRaises25P03_whichNamesTheTransaction() throws Exception {
        try (var connection = inTransaction("set idle_in_transaction_session_timeout = 200")) {
            Thread.sleep(1_000);
            var raised = failureOf(connection::commit);

            assertThat(raised.getSQLState()).isEqualTo("25P03");
            assertThat(OwnedConnections.endedOverTransaction(raised, SQLDialect.POSTGRES)).isTrue();
        }
    }

    @Test
    void transactionTimeout_theRollbackRaises25P04_whichNamesTheTransaction() throws Exception {
        int version;
        try (var probe = open(); var statement = probe.createStatement();
             var rs = statement.executeQuery("select current_setting('server_version_num')::int")) {
            rs.next();
            version = rs.getInt(1);
        }
        assumeThat(version)
            .as("transaction_timeout exists from PostgreSQL 17; this server is " + version)
            .isGreaterThanOrEqualTo(170000);
        try (var connection = inTransaction("set transaction_timeout = 200")) {
            Thread.sleep(1_000);
            var raised = failureOf(connection::rollback);

            assertThat(raised.getSQLState()).isEqualTo("25P04");
            assertThat(OwnedConnections.endedOverTransaction(raised, SQLDialect.POSTGRES)).isTrue();
        }
    }

    @Test
    void terminatedBackend_theRollbackRaisesNothingThatNamesTheTransaction() throws Exception {
        try (var connection = inTransaction(null)) {
            int pid;
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("select pg_backend_pid()")) {
                rs.next();
                pid = rs.getInt(1);
            }
            try (var killer = open()) {
                execute(killer, "select pg_terminate_backend(" + pid + ")");
            }
            Thread.sleep(200);
            var raised = failureOf(connection::rollback);

            assertThat(raised.getSQLState()).isEqualTo("57P01");
            assertThat(OwnedConnections.endedOverTransaction(raised, SQLDialect.POSTGRES))
                .as("a session ended from outside its transaction is outside it")
                .isFalse();
            assertThat(connection.isValid(1))
                .as("and the driver reports the connection no longer valid, which is what release reads")
                .isFalse();
        }
    }

    @Test
    void idleInTransactionTimeout_theFirstStatementAfterRaises25P03_andTheRollbackAfterThat08003() throws Exception {
        // Settle probes the transaction before it ends it: that statement is the one that reads the
        // session's end, and the rollback after it finds the connection closed.
        try (var connection = inTransaction("set idle_in_transaction_session_timeout = 200")) {
            Thread.sleep(1_000);
            var probed = failureOf(() -> execute(connection, "select 1"));
            var rolledBack = failureOf(connection::rollback);

            assertThat(probed.getSQLState()).isEqualTo("25P03");
            assertThat(rolledBack.getSQLState()).isEqualTo("08003");
            assertThat(OwnedConnections.endedOverTransaction(probed, SQLDialect.POSTGRES)).isTrue();
            assertThat(OwnedConnections.endedOverTransaction(rolledBack, SQLDialect.POSTGRES))
                .as("so the rollback alone would not name the transaction; settle reads what the probe raised")
                .isFalse();
            assertThat(connection.isValid(1))
                .as("the connection is no longer valid, so release evicts it without rethrowing")
                .isFalse();
        }
    }
}
