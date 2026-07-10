package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.generated.schema.GraphitronTransactionProvider;
import no.sikt.graphitron.generated.schema.GraphitronTransactionProvider.CommitPolicy;
import no.sikt.graphitron.generated.schema.PinnedConnection;
import no.sikt.graphitron.generated.schema.SessionHook;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R429 rework execution-tier enforcer for the settle re-fire: against real PostgreSQL, a transaction
 * settle that leaves the mounted identity wiped is rescued by the re-fire, so the post-settle
 * read-back stretch always sees mounted identity. The unit tier pins the call ordering over fake JDBC
 * ({@code ConnectionRuntimeClassGeneratorTest}); this class pins the survival property itself, which a
 * call log cannot prove (a re-fire at the wrong point, e.g. inside the settling transaction, would
 * order identically and still lose the state).
 *
 * <p>These tests drive the emitted lifecycle classes directly ({@code PinnedConnection.acquire} with a
 * hand-written probe {@link SessionHook}, the emitted {@code GraphitronTransactionProvider} with
 * {@code pinned::afterSettle} wired, jOOQ transactions through the provider), not the generated
 * engine: this module's engine bakes the {@code <variables>} sugar, which opts in to survival
 * structurally and never re-fires. The engine-to-provider wiring is covered by
 * {@code ConnectionLifecycleExecutionTest}; full-engine function-hook coverage stays unit-tier, the
 * same stance as Oracle/RAS.
 *
 * <p>The probe hook stamps each mount with a generation ({@code gen-1}, {@code gen-2}, ...) in a
 * session variable, so the identity visible after a settle provably comes from a specific
 * (re-)connect. The wipe is a session-scoped clear committed inside the transaction, exactly the
 * shape of hook state that does not survive a settle and the reason the fallback exists.
 */
@ExecutionTier
class SessionHookReFireExecutionTest {

    static PostgreSQLContainer postgres;
    static String jdbcUrl, jdbcUser, jdbcPassword;

    /** The session GUC the probe hook mounts; generation-stamped per connect. */
    private static final String IDENTITY_VAR = "app.refire_probe";

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            jdbcUrl = localUrl;
            jdbcUser = System.getProperty("test.db.username", "postgres");
            jdbcPassword = System.getProperty("test.db.password", "postgres");
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            jdbcUser = postgres.getUsername();
            jdbcPassword = postgres.getPassword();
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void unconfirmedHook_commitWipesIdentity_reFireRemountsForTheReadBackStretch() throws Exception {
        var generation = new AtomicInteger();
        PinnedConnection pinned = PinnedConnection.acquire(
            dataSource(), generationHook(generation), "{}", Runnable::run, true);
        try {
            DSLContext dsl = providerBound(pinned);
            assertThat(identity(dsl)).as("operation SQL sees the acquisition mount").isEqualTo("gen-1");

            // A mutation field's transaction whose COMMIT leaves the mounted identity wiped: the
            // session-scoped clear persists with the commit. Without the re-fire, everything after
            // this settle (the field's payload read-back, later serial fields) would run identityless.
            dsl.transaction(tx -> tx.dsl()
                .execute("select set_config('" + IDENTITY_VAR + "', '', false)"));

            assertThat(identity(dsl))
                .as("the post-commit read-back stretch sees the re-fired mount, not the wiped one")
                .isEqualTo("gen-2");
        } finally {
            pinned.release();
        }
    }

    @Test
    void unconfirmedHook_rollbackSettle_alsoReFires() throws Exception {
        var generation = new AtomicInteger();
        PinnedConnection pinned = PinnedConnection.acquire(
            dataSource(), generationHook(generation), "{}", Runnable::run, true);
        try {
            DSLContext dsl = providerBound(pinned);

            // A failing mutation field: its transaction rolls back (the wipe inside it reverts with
            // the rollback, so gen-1 would survive here even without the re-fire). Asserting gen-2
            // distinguishes: the re-fire runs on the rollback settle too, and it runs OUTSIDE the
            // rolled-back transaction, or its own mount would have reverted with it.
            assertThatThrownBy(() -> dsl.transaction(tx -> {
                tx.dsl().execute("select set_config('" + IDENTITY_VAR + "', '', false)");
                throw new IllegalStateException("field failed");
            })).hasMessageContaining("field failed");

            assertThat(identity(dsl))
                .as("identity after a rollback settle is the re-fired mount")
                .isEqualTo("gen-2");
        } finally {
            pinned.release();
        }
    }

    @Test
    void confirmedHook_control_theWipeIsRealAndWouldPersistWithoutTheReFire() throws Exception {
        var generation = new AtomicInteger();
        // remountAfterSettle=false is what the runtime bakes for <stateSurvivesTransactions>true</>:
        // no re-fire. This control proves the wiping settle in the tests above is a real state loss
        // (the enforcer cannot pass vacuously) and shows exactly what a wrongly-confirmed hook risks.
        PinnedConnection pinned = PinnedConnection.acquire(
            dataSource(), generationHook(generation), "{}", Runnable::run, false);
        try {
            DSLContext dsl = providerBound(pinned);
            assertThat(identity(dsl)).isEqualTo("gen-1");

            dsl.transaction(tx -> tx.dsl()
                .execute("select set_config('" + IDENTITY_VAR + "', '', false)"));

            assertThat(identity(dsl))
                .as("without the re-fire, the committed wipe leaves the read-back stretch identityless")
                .isEmpty();
        } finally {
            pinned.release();
        }
    }

    // ===== helpers =====

    /** Binds a jOOQ context to the pinned connection with the emitted provider and settle callback, as the instrumentation does. */
    private static DSLContext providerBound(PinnedConnection pinned) {
        Connection connection = pinned.connection();
        DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
        dsl.configuration().set(new GraphitronTransactionProvider(connection, CommitPolicy.COMMIT, pinned::afterSettle));
        return dsl;
    }

    /** Reads the mounted identity in autocommit, as a read-back projection would. */
    private static String identity(DSLContext dsl) {
        return dsl.fetchOne("select coalesce(current_setting('" + IDENTITY_VAR + "', true), '')")
            .get(0, String.class);
    }

    /**
     * The probe hook: connect stamps {@code app.refire_probe} with a fresh generation and returns it
     * as the handle; disconnect clears the variable. Session-scoped state, per the hook contract.
     */
    private static SessionHook generationHook(AtomicInteger generation) {
        return new SessionHook() {
            @Override
            public String connect(Connection connection, String claims) throws SQLException {
                String stamp = "gen-" + generation.incrementAndGet();
                try (PreparedStatement ps = connection.prepareStatement(
                        "select set_config('" + IDENTITY_VAR + "', ?, false)")) {
                    ps.setString(1, stamp);
                    ps.execute();
                }
                return stamp;
            }

            @Override
            public void disconnect(Connection connection, String handle) throws SQLException {
                try (PreparedStatement ps = connection.prepareStatement(
                        "select set_config('" + IDENTITY_VAR + "', '', false)")) {
                    ps.execute();
                }
            }
        };
    }

    /** A DataSource handing out fresh physical connections; the emitted runtime owns their lifecycle. */
    private static DataSource dataSource() {
        return (DataSource) Proxy.newProxyInstance(
            SessionHookReFireExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConnection" -> DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "reFireProbeDataSource";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
