package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof of the session-identity example for the escape-hatch path, the section
 * "On the escape-hatch path" under Row-Level Security in
 * {@code docs/architecture/reference/runtime-extension-points.adoc}. {@link #executeAs} mirrors
 * that example line for line; a change to either belongs in both.
 *
 * <p>On the escape-hatch path the consumer passes its own {@code DSLContext} to
 * {@code Graphitron.newExecutionInput} and owns the connection, its transactions and the identity
 * on it; Graphitron runs no mount or unmount. Each case runs the {@code createSecureNote} routine
 * mutation, whose post-commit re-read runs on the consumer's connection under the {@code secure_note}
 * policy from {@code init.sql}: the re-read returns the row only while {@code app.user_id} still
 * names its owner. The documented form keeps identity across the mutation's commit and clears it
 * before the connection goes back; the two other forms pin the reasons the documentation gives
 * for not using them, so a jOOQ or Graphitron change that alters those reasons fails here rather
 * than leaving the prose wrong.
 *
 * <p>RLS bypass note: superusers bypass row-level security, so every case runs as a dedicated
 * non-superuser role. Each case writes a note under its own marker text and deletes by that text,
 * never the whole table, since other classes share the database.
 */
@ExecutionTier
class EscapeHatchSessionIdentityExecutionTest {

    static DSLContext superDsl;            // superuser: out-of-band setup, assertions, cleanup
    static String jdbcUrl;
    static GraphQL graphql;
    static final String PROBE_USER = "escape_hatch_identity_probe";
    static final String PROBE_PASSWORD = "probe";

    /** The caller; its own name, so classes counting another caller's visible notes never see these. */
    static final String CALLER = "escape-hatch-alice";
    static final String DOCUMENTED = "escape hatch: session-scoped and cleared";
    static final String AUTOCOMMIT_LOCAL = "escape hatch: transaction-scoped in autocommit";
    static final String REQUEST_TRANSACTION = "escape hatch: transaction-scoped in a request-wide transaction";
    static final List<String> MARKERS = List.of(DOCUMENTED, AUTOCOMMIT_LOCAL, REQUEST_TRANSACTION);

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = SakilaDatabase.url();
        superDsl = DSL.using(jdbcUrl, SakilaDatabase.user(), SakilaDatabase.password());

        // The table, routine and policy live in init.sql; the role and its grants are this test's.
        dropProbeRole();
        superDsl.execute("create role " + PROBE_USER + " login password '" + PROBE_PASSWORD + "'");
        superDsl.execute("grant select on secure_note to " + PROBE_USER);
        superDsl.execute("grant execute on function create_secure_note(text, text) to " + PROBE_USER);

        graphql = Graphitron.newGraphQL().build();
    }

    @AfterEach
    void deleteOwnNotes() {
        superDsl.deleteFrom(DSL.table("secure_note"))
            .where(DSL.field("note", String.class).in(MARKERS))
            .execute();
    }

    @AfterAll
    static void stopDatabase() {
        if (superDsl != null) {
            dropProbeRole();
        }
    }

    private static void dropProbeRole() {
        superDsl.execute("""
            do $$ begin
              if exists (select from pg_roles where rolname = '%s') then
                drop owned by %s;
              end if;
            end $$""".formatted(PROBE_USER, PROBE_USER));
        superDsl.execute("drop role if exists " + PROBE_USER);
    }

    @Test
    void documentedForm_identitySurvivesTheMutationsCommit_andIsClearedBeforeTheConnectionGoesBack()
            throws Exception {
        try (Connection raw = probeConnection()) {
            var pool = new PoolOfOne(raw);

            ExecutionResult result = executeAs(pool, CALLER, createSecureNote(DOCUMENTED));

            assertThat(reReadNote(result))
                .as("the post-commit re-read still runs as the caller: identity survived the mutation's commit")
                .isEqualTo(DOCUMENTED);
            assertThat(pool.returned).as("handed back with close(), not evicted").isEqualTo(1);
            assertThat(pool.aborted).isFalse();
            assertThat(userIdSetting(raw))
                .as("the pool's next borrower on this physical connection finds no identity")
                .isEmpty();
        }
    }

    @Test
    void transactionScopedSettingInAutocommit_revertsBeforeGraphitronRunsAnything() throws Exception {
        // The example's shape before it was fixed: set_config(..., true) on a connection in
        // autocommit lasts for its own statement only.
        try (Connection conn = probeConnection()) {
            DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
            ctx.execute("select set_config('app.user_id', ?, true)", CALLER);

            ExecutionResult result = graphql.execute(Graphitron.newExecutionInput(ctx, "{}", CALLER)
                .query(createSecureNote(AUTOCOMMIT_LOCAL)).build());

            assertThat(reReadNote(result))
                .as("the re-read runs with no identity, and the fail-closed policy hides the row")
                .isNull();
        }
    }

    @Test
    void transactionScopedSettingInARequestWideTransaction_endsAtTheFirstMutationsCommit() throws Exception {
        // The form that cannot leak by construction, and why the example does not use it: the
        // generated mutation commits the consumer's transaction, and the identity in it, when its
        // own transaction scope on the consumer's DSLContext closes.
        try (Connection conn = probeConnection()) {
            conn.setAutoCommit(false);
            DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
            ctx.execute("select set_config('app.user_id', ?, true)", CALLER);
            assertThat(userIdSetting(conn))
                .as("control: inside the request-wide transaction, identity is there before the mutation")
                .isEqualTo(CALLER);

            ExecutionResult result = graphql.execute(Graphitron.newExecutionInput(ctx, "{}", CALLER)
                .query(createSecureNote(REQUEST_TRANSACTION)).build());

            assertThat(reReadNote(result))
                .as("the mutation's commit ended the transaction and the identity with it")
                .isNull();
            conn.rollback();
        }
    }

    /**
     * The documented example, as code: session-scoped identity on the escape-hatch
     * {@code DSLContext}, set in autocommit before the request runs and cleared in autocommit
     * after it, evicting the connection when the clear cannot be shown to have happened.
     */
    private static ExecutionResult executeAs(javax.sql.DataSource dataSource, String userId, String query)
            throws Exception {
        ExecutionResult result;
        Connection conn = dataSource.getConnection();
        try {
            settle(conn);
            DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
            ctx.execute("select set_config('app.user_id', ?, false)", userId); // false: session-scoped
            var input = Graphitron.newExecutionInput(ctx, "{}", userId).query(query).build();
            result = graphql.execute(input);
        } finally {
            try {
                settle(conn);
                DSL.using(conn, SQLDialect.POSTGRES).execute("select set_config('app.user_id', '', false)");
                conn.close();
            } catch (Exception clearFailed) {
                conn.abort(Runnable::run); // identity not proven cleared: evict, never hand it on
                throw clearFailed;
            }
        }
        return result;
    }

    /** Autocommit on, with nothing left open; rolls back first, since switching commits. */
    private static void settle(Connection conn) throws SQLException {
        if (!conn.getAutoCommit()) {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    private static String createSecureNote(String note) {
        return """
            mutation {
                createSecureNote(owner: "%s", note: "%s") {
                    secureNote { note }
                    errors { __typename }
                }
            }
            """.formatted(CALLER, note);
    }

    /** The note the mutation's post-commit re-read returned, or null when the policy hid it. */
    @SuppressWarnings("unchecked")
    private static String reReadNote(ExecutionResult result) {
        assertThat(result.getErrors()).as("top-level errors: %s", result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var payload = (Map<String, Object>) data.get("createSecureNote");
        assertThat(payload).as("the routine committed and returned its key").isNotNull();
        var secureNote = (Map<String, Object>) payload.get("secureNote");
        return secureNote == null ? null : (String) secureNote.get("note");
    }

    private static Connection probeConnection() throws SQLException {
        return SakilaDatabase.connect(jdbcUrl, PROBE_USER, PROBE_PASSWORD);
    }

    /** The identity setting, as a borrower that sets none would read it. */
    private static String userIdSetting(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select current_setting('app.user_id', true)")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /**
     * A pool of one: hands out a wrapper over {@code raw} whose {@code close()} returns it to the
     * pool without closing the physical connection, so the test reads what the next borrower
     * would see on the same session; {@code abort} is recorded instead of performed.
     */
    private static final class PoolOfOne implements javax.sql.DataSource {
        private final Connection raw;
        int returned;
        boolean aborted;

        PoolOfOne(Connection raw) {
            this.raw = raw;
        }

        @Override public Connection getConnection() {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> { returned++; yield null; }
                    case "abort" -> { aborted = true; yield null; }
                    default -> {
                        try {
                            yield method.invoke(raw, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
        }
        @Override public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
