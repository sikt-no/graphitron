package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.generated.schema.GraphitronSessionHook;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R429 slice 3 execution-tier coverage of the generated session hook against real PostgreSQL with
 * row-level security. This module configures the Postgres {@code <variables>} sugar
 * ({@code <sessionState>} in the pom), so graphitron emits a {@code GraphitronSessionHook} whose
 * connect sets {@code app.user_id} from the JWT {@code sub} claim and whose disconnect clears it. The
 * test drives that real emitted hook, not a hand-written equivalent.
 *
 * <p>RLS bypass note: PostgreSQL superusers (and the {@code postgres} role the pooled test DataSource
 * uses) bypass RLS, so these tests open connections as a dedicated non-superuser role against a probe
 * table with a policy keyed on {@code app.user_id}. The three assertions mirror the slice-3 spec: an
 * RLS-scoped read sees only permitted rows; a mutation's post-commit read-back still sees only
 * permitted rows; and identity is demonstrably absent (fail closed) after disconnect, with the next
 * acquisition of the same physical connection mounting a different identity.
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

        // A probe table with an RLS policy keyed on app.user_id, and a non-superuser role to query it
        // under (superusers bypass RLS). The policy treats both NULL and the empty string as no identity,
        // the fail-closed pattern the <variables> sugar's disconnect (clear to empty string) relies on.
        exec("drop table if exists rls_probe");
        exec("drop role if exists " + PROBE_USER);
        exec("create role " + PROBE_USER + " login password '" + PROBE_PASSWORD + "'");
        exec("create table rls_probe (id int primary key, owner_id text not null, note text not null)");
        exec("alter table rls_probe enable row level security");
        exec("alter table rls_probe force row level security");
        exec("create policy p_owner on rls_probe "
            + "using (owner_id = nullif(current_setting('app.user_id', true), '')) "
            + "with check (owner_id = nullif(current_setting('app.user_id', true), ''))");
        exec("grant select, insert, update, delete on rls_probe to " + PROBE_USER);
        exec("insert into rls_probe values (1,'alice','a1'),(2,'bob','b1'),(3,'alice','a2')");
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            exec("drop table if exists rls_probe");
            exec("drop role if exists " + PROBE_USER);
        }
        if (postgres != null) postgres.stop();
    }

    @Test
    void rlsRead_afterConnect_seesOnlyPermittedRows() throws Exception {
        var hook = new GraphitronSessionHook();
        try (Connection conn = probeConnection()) {
            // Before any identity is mounted, RLS denies everything (fail closed).
            assertThat(notesVisible(conn)).as("no identity mounted").isEmpty();

            String handle = hook.connect(conn, "{\"sub\":\"alice\"}");
            assertThat(handle).as("the <variables> sugar carries no handle").isNull();

            assertThat(notesVisible(conn))
                .as("the mounted identity scopes the read to alice's rows")
                .contains("a1", "a2")
                .doesNotContain("b1");
        }
    }

    @Test
    void mutationReadBack_underMountedIdentity_seesOnlyPermittedRows() throws Exception {
        var hook = new GraphitronSessionHook();
        try (Connection conn = probeConnection()) {
            hook.connect(conn, "{\"sub\":\"alice\"}");
            // A mutation field's writable transaction commits, then its payload read-back runs under the
            // still-mounted identity: the just-committed alice row is visible, bob's rows are not.
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
        } finally {
            dsl.execute("delete from rls_probe where id = 100"); // superuser cleanup, bypasses RLS
        }
    }

    @Test
    void identityAbsentAfterDisconnect_andNextAcquisitionMountsFreshIdentity() throws Exception {
        var hook = new GraphitronSessionHook();
        try (Connection conn = probeConnection()) {
            hook.connect(conn, "{\"sub\":\"alice\"}");
            assertThat(notesVisible(conn)).contains("a1");

            hook.disconnect(conn, null);
            assertThat(notesVisible(conn))
                .as("after disconnect the identity is gone: RLS denies everything (fail closed)")
                .isEmpty();

            // The pool hands this same physical connection to the next borrower; a fresh connect mounts a
            // different identity and sees only that identity's rows. Identity is acquisition-scoped.
            hook.connect(conn, "{\"sub\":\"bob\"}");
            assertThat(notesVisible(conn))
                .as("the next acquisition sees bob's rows, never alice's leftover state")
                .contains("b1")
                .doesNotContain("a1", "a2");
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
}
