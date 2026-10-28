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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Execution-tier coverage of the emitted {@link GraphitronTransactionProvider}'s failure path
 * against real PostgreSQL, the half no fake connection can pin: that pgjdbc reports a savepoint
 * release over an aborted transaction as a failure the provider rethrows rather than tolerates.
 * Swallowed, that release would let the enclosing {@code COMMIT} report success while Postgres
 * silently rolled the whole transaction back. The tolerated driver-unsupported case (Oracle) and
 * the provider's bookkeeping are pinned at the unit tier in
 * {@code GraphitronTransactionProviderGeneratorTest}.
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
    void nestedCommitOverAnAbortedTransaction_surfaces_andTheEnclosingTransactionRecovers() throws SQLException {
        try (Connection connection = SakilaDatabase.connect(jdbcUrl, jdbcUser, jdbcPassword)) {
            var provider = new GraphitronTransactionProvider(connection, CommitPolicy.COMMIT);
            DSLContext ctx = DSL.using(new DefaultConfiguration()
                .set(connection)
                .set(SQLDialect.POSTGRES)
                .set(provider));
            var nestedFailure = new AtomicReference<DataAccessException>();

            ctx.transaction(outer -> {
                outer.dsl().execute("insert into tx_provider.marker values (1)");
                try {
                    outer.dsl().transaction(nested -> {
                        try {
                            nested.dsl().execute("select 1 / 0");
                        } catch (DataAccessException swallowedByServiceCode) {
                            // The service catches the failed statement and returns normally,
                            // leaving the Postgres transaction aborted.
                        }
                    });
                } catch (DataAccessException e) {
                    nestedFailure.set(e);
                }
            });

            assertThat(nestedFailure.get())
                .as("the nested commit over an aborted transaction surfaces instead of being swallowed")
                .isNotNull()
                .hasMessageContaining("Could not commit transaction");
            assertThat(nestedFailure.get().getCause())
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .as("pgjdbc reports the release as in_failed_sql_transaction, not as an unsupported feature")
                .isEqualTo("25P02");
            assertThat(markers())
                .as("the nested scope rolled back to its savepoint, so the enclosing write committed")
                .containsExactly(1);

            ctx.transaction(second -> second.dsl().execute("insert into tx_provider.marker values (2)"));

            assertThat(markers())
                .as("the provider's scope stack came back consistent for the next transaction")
                .containsExactly(1, 2);
            assertThat(connection.getAutoCommit()).isTrue();
        } finally {
            dsl.execute("delete from tx_provider.marker");
        }
    }

    @Test
    void nestedOpenOverAnAbortedTransaction_failsAlone_andTheEnclosingScopeRollsBackOnce() throws SQLException {
        try (Connection connection = SakilaDatabase.connect(jdbcUrl, jdbcUser, jdbcPassword)) {
            var provider = new GraphitronTransactionProvider(connection, CommitPolicy.COMMIT);
            DSLContext ctx = DSL.using(new DefaultConfiguration()
                .set(connection)
                .set(SQLDialect.POSTGRES)
                .set(provider));
            var nestedFailure = new AtomicReference<DataAccessException>();

            Throwable thrown = catchThrowable(() -> ctx.transaction(outer -> {
                outer.dsl().execute("insert into tx_provider.marker values (1)");
                try {
                    outer.dsl().execute("select 1 / 0");
                } catch (DataAccessException swallowedByServiceCode) {
                    // The service catches the failed statement, leaving the transaction aborted,
                    // and then opens a nested transaction: Postgres refuses the savepoint.
                }
                try {
                    outer.dsl().transaction(nested -> nested.dsl().execute("insert into tx_provider.marker values (3)"));
                } catch (DataAccessException e) {
                    nestedFailure.set(e);
                    throw e;
                }
            }));

            assertThat(thrown)
                .as("the failed open propagates out of the outer transaction as itself")
                .isNotNull()
                .isSameAs(nestedFailure.get())
                .hasMessageContaining("Could not begin transaction");
            assertThat(nestedFailure.get().getSuppressed())
                .as("jOOQ's rollback for the open that failed found no scope, so it had nothing to fail")
                .isEmpty();
            assertThat(nestedFailure.get().getCause())
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
