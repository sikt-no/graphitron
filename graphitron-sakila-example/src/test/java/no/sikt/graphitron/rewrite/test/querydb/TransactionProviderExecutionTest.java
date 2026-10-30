package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.generated.schema.GraphitronTransactionProvider;
import no.sikt.graphitron.generated.schema.GraphitronTransactionProvider.CommitPolicy;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Execution-tier coverage of the emitted {@link GraphitronTransactionProvider}'s failure path
 * against real PostgreSQL, the half no fake connection can pin: how the join rule meets a
 * transaction pgjdbc has aborted. A nested scope that fails makes the enclosing commit refuse, so
 * the failure the service swallowed surfaces instead of the enclosing {@code COMMIT} reporting
 * success while Postgres silently rolls the whole transaction back; and a nested scope opened over
 * an aborted transaction joins, so there is no savepoint for Postgres to refuse. The provider's
 * bookkeeping is pinned at the unit tier in {@code GraphitronTransactionProviderGeneratorTest}.
 *
 * <p>Writes go to a throwaway {@code tx_provider.marker} table, dropped after the class, since the
 * transactions under test commit for real.
 */
@ExecutionTier
class TransactionProviderExecutionTest {

    static DSLContext dsl;                 // out-of-band setup, observation and cleanup
    static String jdbcUrl, jdbcUser, jdbcPassword;

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = SakilaDatabase.url();
        jdbcUser = SakilaDatabase.user();
        jdbcPassword = SakilaDatabase.password();

        dsl = DSL.using(jdbcUrl, jdbcUser, jdbcPassword);

        dsl.execute("drop schema if exists tx_provider cascade");
        dsl.execute("create schema tx_provider");
        dsl.execute("create table tx_provider.marker (id int primary key)");
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            dsl.execute("drop schema if exists tx_provider cascade");
        }
    }

    @Test
    void aNestedFailure_onPostgres_failsTheEnclosingCommit_andTheProviderRecovers() throws SQLException {
        try (Connection connection = SakilaDatabase.connect(jdbcUrl, jdbcUser, jdbcPassword)) {
            var provider = new GraphitronTransactionProvider(connection, CommitPolicy.COMMIT, caller -> { });
            DSLContext ctx = DSL.using(new DefaultConfiguration()
                .set(connection)
                .set(SQLDialect.POSTGRES)
                .set(provider));

            Throwable thrown = catchThrowable(() -> ctx.transaction(outer -> {
                outer.dsl().execute("insert into tx_provider.marker values (1)");
                try {
                    outer.dsl().transaction(nested -> nested.dsl().execute("select 1 / 0"));
                } catch (DataAccessException swallowedByServiceCode) {
                    // The service catches the nested failure and returns normally.
                }
            }));

            assertThat(thrown)
                .as("the failure the service swallowed surfaces when the enclosing transaction cannot commit")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("nested transaction failed");
            assertThat(thrown.getCause())
                .as("the nested scope's own failure is its cause")
                .isInstanceOf(DataAccessException.class);
            assertThat(markers())
                .as("the enclosing transaction rolled back with the nested scope: nothing stays")
                .isEmpty();

            ctx.transaction(second -> second.dsl().execute("insert into tx_provider.marker values (2)"));

            assertThat(markers())
                .as("the provider's scope stack came back consistent for the next transaction")
                .containsExactly(2);
            assertThat(connection.getAutoCommit()).isTrue();
        } finally {
            dsl.execute("delete from tx_provider.marker");
        }
    }

    @Test
    void nestedScope_overAnAbortedTransaction_joinsAndTheStatementItRunsFailsAlone() throws SQLException {
        try (Connection connection = SakilaDatabase.connect(jdbcUrl, jdbcUser, jdbcPassword)) {
            var provider = new GraphitronTransactionProvider(connection, CommitPolicy.COMMIT, caller -> { });
            DSLContext ctx = DSL.using(new DefaultConfiguration()
                .set(connection)
                .set(SQLDialect.POSTGRES)
                .set(provider));

            Throwable thrown = catchThrowable(() -> ctx.transaction(outer -> {
                outer.dsl().execute("insert into tx_provider.marker values (1)");
                try {
                    outer.dsl().execute("select 1 / 0");
                } catch (DataAccessException swallowedByServiceCode) {
                    // The service catches the failed statement, leaving the transaction aborted,
                    // and then opens a nested transaction: it used to take a savepoint, which
                    // Postgres refuses. It joins now, so opening it cannot fail.
                }
                outer.dsl().transaction(nested -> nested.dsl().execute("insert into tx_provider.marker values (3)"));
            }));

            assertThat(thrown)
                .as("the statement inside the nested scope fails on the aborted transaction, and the"
                    + " failure leaves the outer scope as itself")
                .isInstanceOf(DataAccessException.class);
            assertThat(thrown.getCause())
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo("25P02");
            assertThat(markers())
                .as("the enclosing transaction rolled back, once, as itself")
                .isEmpty();

            ctx.transaction(second -> second.dsl().execute("insert into tx_provider.marker values (2)"));

            assertThat(markers())
                .as("the provider's scope stack came back consistent for the next transaction")
                .containsExactly(2);
            assertThat(connection.getAutoCommit()).isTrue();
        } finally {
            dsl.execute("delete from tx_provider.marker");
        }
    }

    private static List<Integer> markers() {
        return dsl.fetch("select id from tx_provider.marker order by id").getValues("id", Integer.class);
    }
}
