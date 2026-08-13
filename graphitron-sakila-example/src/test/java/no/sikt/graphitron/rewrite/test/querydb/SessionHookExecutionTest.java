package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.jooq.udt.records.SessionClaimsRecord;
import no.sikt.graphitron.rewrite.test.jooq.udt.records.SessionHandleRecord;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Execution-tier proof of the {@code <sessionState>} method-hook form against real PostgreSQL
 * with row-level security: the first end-to-end run the form has ever had. This module
 * configures both shapes of the one contract, and this test drives both real emitted hook
 * classes, never a hand-written equivalent:
 *
 * <ul>
 *   <li>the hand-written facade shape ({@code SakilaSessionIdentity#mount}, one String claims
 *       payload reshaped into the composite the routine takes), emitted into the main package's
 *       {@code GraphitronSessionHook}; and</li>
 *   <li>the direct jOOQ-{@code Routines} shape ({@code Routines#sessionConnect}, composite
 *       payload, zero hand-written Java), emitted into the multischema-mutation package's
 *       {@code GraphitronSessionHook} — same routines, same database, so "generated and
 *       hand-written are indistinguishable to the resolver" is proven by both round-tripping.</li>
 * </ul>
 *
 * <p>RLS bypass note: PostgreSQL superusers bypass RLS, so these tests open connections as a
 * dedicated non-superuser role against a probe table with a policy keyed on {@code app.user_id}
 * (which {@code session_connect} mounts, session-scoped, and {@code session_disconnect} clears
 * to the empty string; the policy treats {@code NULL} and {@code ''} identically as no
 * identity, the fail-closed pattern). The assertions mirror the spec's execution rows: an
 * RLS-scoped read sees only permitted rows; a mutation's post-commit read-back on the same
 * connection still does, with no hook having run in between (the autocommit mount is what
 * carries identity across a transaction boundary), and the rollback complement holds too;
 * identity is absent after unmount, and the next mount overwrites wholesale; and a throwing
 * mount fails closed through the emitted {@code PinnedConnection.acquire}, evicting the
 * connection. The acquire rows run against a {@code DataSource} handing out
 * {@code autoCommit=false} connections, the fixture in which deleting the acquire-time
 * assertion would show up as a failure instead of passing on the pool's default.
 */
@ExecutionTier
class SessionHookExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;                 // superuser, out-of-band setup and cleanup (bypasses RLS)
    static String jdbcUrl;
    static final String PROBE_USER = "rls_probe_user";
    static final String PROBE_PASSWORD = "probe";

    @BeforeAll
    static void startDatabase() throws SQLException {
        var localUrl = System.getProperty("test.db.url");
        String user, password;
        if (localUrl != null) {
            jdbcUrl = localUrl;
            user = System.getProperty("test.db.username", "postgres");
            password = System.getProperty("test.db.password", "postgres");
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            user = postgres.getUsername();
            password = postgres.getPassword();
        }
        dsl = DSL.using(jdbcUrl, user, password);

        // A probe table with an RLS policy keyed on app.user_id, and a non-superuser role to
        // query it under (superusers bypass RLS). The policy treats both NULL and the empty
        // string as no identity: a touched placeholder GUC cannot be returned to unset, so the
        // unmount's clear-to-empty-string and a never-mounted connection must read identically.
        exec("drop table if exists rls_probe");
        dropProbeRole();
        exec("create role " + PROBE_USER + " login password '" + PROBE_PASSWORD + "'");
        exec("create table rls_probe (id int primary key, owner_id text not null, note text not null)");
        exec("alter table rls_probe enable row level security");
        exec("alter table rls_probe force row level security");
        exec("create policy p_owner on rls_probe "
            + "using (owner_id = nullif(current_setting('app.user_id', true), '')) "
            + "with check (owner_id = nullif(current_setting('app.user_id', true), ''))");
        exec("grant select, insert, update, delete on rls_probe to " + PROBE_USER);
        exec("grant usage on sequence session_handle_seq to " + PROBE_USER);
        exec("insert into rls_probe values (1,'alice','a1'),(2,'bob','b1'),(3,'alice','a2')");
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            exec("drop table if exists rls_probe");
            dropProbeRole();
        }
        if (postgres != null) postgres.stop();
    }

    /**
     * Drops the probe role, first shedding any privileges it holds (the sequence usage grant
     * above blocks a bare {@code drop role}). The role may not exist on a fresh database, so
     * the shed is conditional.
     */
    private static void dropProbeRole() {
        exec("do $$ begin if exists (select from pg_roles where rolname = '" + PROBE_USER + "')"
            + " then execute 'drop owned by " + PROBE_USER + "'; end if; end $$");
        exec("drop role if exists " + PROBE_USER);
    }

    @Test
    void facadeShape_mountsScopesReadsAndRoundTripsTheCompositeHandle() throws Exception {
        try (Connection conn = probeConnection()) {
            // Before any identity is mounted, RLS denies everything (fail closed).
            assertThat(notesVisible(conn)).as("no identity mounted").isEmpty();

            SessionHandleRecord handle = no.sikt.graphitron.generated.schema.GraphitronSessionHook
                .mount(conn, SQLDialect.POSTGRES, new Settings(), "{\"sub\":\"alice\"}");
            assertThat(handle.getPrincipal())
                .as("the handle is the mount's own resolved identity, typed, never opaque")
                .isEqualTo("alice");
            assertThat(handle.getSessionNo()).isNotNull();

            assertThat(notesVisible(conn))
                .as("the mounted identity scopes the read to alice's rows")
                .contains("a1", "a2")
                .doesNotContain("b1");

            no.sikt.graphitron.generated.schema.GraphitronSessionHook
                .unmount(conn, SQLDialect.POSTGRES, new Settings(), handle);
            assertThat(notesVisible(conn))
                .as("after unmount the identity is gone: RLS denies everything (fail closed)")
                .isEmpty();
        }
    }

    @Test
    void directRoutinesShape_isIndistinguishableFromTheFacadeAtTheDatabase() throws Exception {
        // The multischema-mutation package's hook names the jOOQ-generated executing method
        // directly; the payload is the routine's own composite record. Same round trip.
        try (Connection conn = probeConnection()) {
            SessionHandleRecord handle = no.sikt.graphitron.generated.multischemamutation.schema
                .GraphitronSessionHook.mount(conn, SQLDialect.POSTGRES, new Settings(),
                    new SessionClaimsRecord("bob", null));
            assertThat(handle.getPrincipal()).isEqualTo("bob");

            assertThat(notesVisible(conn))
                .as("the direct-Routines mount scopes exactly as the facade does")
                .contains("b1")
                .doesNotContain("a1", "a2");

            no.sikt.graphitron.generated.multischemamutation.schema.GraphitronSessionHook
                .unmount(conn, SQLDialect.POSTGRES, new Settings(), handle);
            assertThat(notesVisible(conn)).isEmpty();
        }
    }

    @Test
    void identityCarriesAcrossTransactionBoundaries_withNoHookRunningInBetween() throws Exception {
        try (Connection conn = probeConnection()) {
            var handle = no.sikt.graphitron.generated.schema.GraphitronSessionHook
                .mount(conn, SQLDialect.POSTGRES, new Settings(), "{\"sub\":\"alice\"}");

            // A mutation field's transaction commits; the post-commit read-back on the same
            // connection still sees RLS-scoped rows, with no hook having run in between: the
            // mount ran in autocommit as its own committed transaction, so a later settle has
            // nothing of the mount's to revert.
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("insert into rls_probe values (100, 'alice', 'a-committed')");
            }
            conn.commit();
            conn.setAutoCommit(true);
            assertThat(notesVisible(conn))
                .as("post-commit read-back sees the new alice row but not bob's")
                .contains("a-committed")
                .doesNotContain("b1");

            // The rollback complement: a failing mutation field rolls its transaction back, and
            // a later read is still RLS-scoped under the same mounted identity.
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("insert into rls_probe values (101, 'alice', 'a-discarded')");
            }
            conn.rollback();
            conn.setAutoCommit(true);
            assertThat(notesVisible(conn))
                .as("a rolled-back transaction cannot revert the mount: the read stays scoped")
                .contains("a1", "a2")
                .doesNotContain("a-discarded", "b1");

            no.sikt.graphitron.generated.schema.GraphitronSessionHook
                .unmount(conn, SQLDialect.POSTGRES, new Settings(), handle);
        } finally {
            dsl.execute("delete from rls_probe where id in (100, 101)"); // superuser cleanup
        }
    }

    @Test
    void nextMountOverwritesWholesale_theMountOnlyContractsGround() throws Exception {
        try (Connection conn = probeConnection()) {
            var hook = no.sikt.graphitron.generated.schema.GraphitronSessionHook.class;
            var mount = hook.getMethod("mount",
                Connection.class, SQLDialect.class, Settings.class, String.class);
            mount.invoke(null, conn, SQLDialect.POSTGRES, new Settings(), "{\"sub\":\"alice\"}");
            assertThat(notesVisible(conn)).contains("a1");

            // The same mount method runs on every acquisition, so the set of state it writes is
            // constant and the overwrite is total: bob's mount leaves nothing of alice's behind.
            mount.invoke(null, conn, SQLDialect.POSTGRES, new Settings(), "{\"sub\":\"bob\"}");
            assertThat(notesVisible(conn))
                .as("the next acquisition sees bob's rows, never alice's leftover state")
                .contains("b1")
                .doesNotContain("a1", "a2");
        }
    }

    @Test
    void throwingMount_failsClosedThroughTheEmittedAcquire_evictingTheConnection() throws Exception {
        // Drives the emitted PinnedConnection.acquire against a DataSource that hands out
        // autoCommit=false connections: the acquire-time autocommit assertion is load-bearing
        // here, and session_connect raises for the 'reject-me' principal, so the acquire must
        // evict (abort closes the physical connection) and propagate before any operation SQL.
        Connection raw = probeConnection();
        raw.setAutoCommit(false);
        Executor sameThread = Runnable::run;
        try {
            assertThatThrownBy(() -> no.sikt.graphitron.generated.schema.PinnedConnection.acquire(
                    singleConnectionDataSource(raw), SQLDialect.POSTGRES, new Settings(), sameThread,
                    "{\"sub\":\"reject-me\"}"))
                .as("the mount's own exception propagates, verbatim cause included")
                .hasStackTraceContaining("unentitled principal");
            assertThat(raw.isClosed())
                .as("fail closed: the half-mounted connection is evicted, never pooled")
                .isTrue();
        } finally {
            if (!raw.isClosed()) {
                raw.close();
            }
        }
    }

    @Test
    void successfulAcquire_assertsAutocommitBeforeTheMount() throws Exception {
        // The companion positive case on the same autoCommit=false DataSource: the mount runs
        // in autocommit (its set_config survives, scoping reads), and release unmounts.
        Connection raw = probeConnection();
        raw.setAutoCommit(false);
        Executor sameThread = Runnable::run;
        var pinned = no.sikt.graphitron.generated.schema.PinnedConnection.acquire(
            singleConnectionDataSource(raw), SQLDialect.POSTGRES, new Settings(), sameThread,
            "{\"sub\":\"alice\"}");
        assertThat(raw.getAutoCommit())
            .as("acquire asserts the resting state on the owned connection")
            .isTrue();
        assertThat(notesVisible(raw)).contains("a1").doesNotContain("b1");
        assertThat(pinned.handle().getPrincipal()).isEqualTo("alice");
        pinned.release();
        assertThat(raw.isClosed()).as("release returns the connection (close) after unmount").isTrue();
    }

    @Test
    void sessionBoundServiceParameter_readsTheMountedHandleThroughTheEngine() throws Exception {
        // The $session round trip through the real owned-connection engine: the emitted service
        // call site reads the handle off the pinned connection's carrier entry, so the service
        // observes the identity the mount itself resolved (principal plus its session number).
        var runtime = no.sikt.graphitron.generated.Graphitron.runtime(
            singleConnectionDataSource(probeConnection()), SQLDialect.POSTGRES);
        var engine = runtime.newGraphQL(
            no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {})).build();

        var result = engine.execute(no.sikt.graphitron.generated.Graphitron
            .newOwnedExecutionInput("{\"sub\":\"alice\"}", "alice")
            .query("{ sessionPrincipal }")
            .build());

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        java.util.Map<String, Object> data = result.getData();
        assertThat((String) data.get("sessionPrincipal"))
            .as("the service saw the mount's own resolved identity")
            .matches("alice#\\d+");
    }

    @Test
    void claimsContextArgumentAtAServiceSite_unifiesWithTheMountPayloadSlot() throws Exception {
        // The payload/contextArgument unification end to end: `claims` names both the mount's
        // payload parameter and a @service contextArguments entry, the factory carries one slot,
        // and the service receives exactly the value the mount was called with.
        String claims = "{\"sub\":\"alice\"}";
        var runtime = no.sikt.graphitron.generated.Graphitron.runtime(
            singleConnectionDataSource(probeConnection()), SQLDialect.POSTGRES);
        var engine = runtime.newGraphQL(
            no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {})).build();

        var result = engine.execute(no.sikt.graphitron.generated.Graphitron
            .newOwnedExecutionInput(claims, "alice")
            .query("{ claimsEcho }")
            .build());

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        java.util.Map<String, Object> data = result.getData();
        assertThat(data.get("claimsEcho")).isEqualTo(claims);
    }

    @Test
    void sessionBoundParameterOnAnEscapeHatchOperation_failsLoudlyInsteadOfBindingNull() throws Exception {
        // The one $session failure that is runtime rather than build-time, because the build
        // cannot see which factory the caller used: a caller-supplied DSLContext (the
        // escape-hatch factory) carries no mounted handle, so the emitted guarded read must
        // throw located rather than hand the service parameter a null.
        try (Connection conn = probeConnection()) {
            DSLContext supplied = DSL.using(conn, SQLDialect.POSTGRES);

            // The emitted guard itself, driven directly: the message names the field
            // coordinate, the sigil, and the owned entry points.
            assertThatThrownBy(() -> no.sikt.graphitron.generated.schema.TenantConnections
                    .sessionHandle(supplied, "Query.sessionPrincipal"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Query.sessionPrincipal")
                .hasMessageContaining("$session")
                .hasMessageContaining("Graphitron.newOwnedExecutionInput")
                .hasMessageContaining("GraphitronRuntime.newGraphQL(schema)");

            // And through the escape-hatch engine end to end: the operation surfaces an error
            // (the fetcher's channel redacts the located throw like any other fault), never
            // data fabricated from a null handle.
            var engine = no.sikt.graphitron.generated.Graphitron.newGraphQL().build();
            var result = engine.execute(no.sikt.graphitron.generated.Graphitron
                .newExecutionInput(supplied, "{\"sub\":\"alice\"}", "alice")
                .query("{ sessionPrincipal }")
                .build());

            assertThat(result.getErrors())
                .as("an escape-hatch $session read is an error, not a silent null")
                .isNotEmpty();
            java.util.Map<String, Object> data = result.getData();
            assertThat(data == null ? null : data.get("sessionPrincipal")).isNull();
        }
    }

    // ===== helpers =====

    private static Connection probeConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, PROBE_USER, PROBE_PASSWORD);
    }

    private static List<String> notesVisible(Connection conn) throws SQLException {
        var notes = new ArrayList<String>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select note from rls_probe")) {
            while (rs.next()) {
                notes.add(rs.getString(1));
            }
        }
        return notes;
    }

    private static void exec(String sql) {
        dsl.execute(sql);
    }

    /** A one-connection DataSource, the shape the dev executor also uses. */
    private static javax.sql.DataSource singleConnectionDataSource(Connection connection) {
        return new javax.sql.DataSource() {
            @Override public Connection getConnection() { return connection; }
            @Override public Connection getConnection(String u, String p) { return connection; }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
