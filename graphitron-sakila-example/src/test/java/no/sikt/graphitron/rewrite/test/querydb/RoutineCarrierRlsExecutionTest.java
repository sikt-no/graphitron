package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof of the routine carrier's other null-data outcome: <b>the routine
 * succeeded and the committed row is invisible to the post-commit re-read</b> — the row-level
 * security path the carrier shape exists for, and the consumer's <em>happy</em> path. Distinct
 * from the routine-raised outcome ({@code GraphQLQueryTest}'s rentFilmPayload error test): here
 * no exception fires, no sentinel is involved, and no field error surfaces; the write commits,
 * step 1 captures a real key inside the transaction, and the data field's post-commit SELECT
 * returns no row because the read policy hides it. The response renders
 * {@code { secureNote: null, errors: null }}.
 *
 * <p>The fixture reproduces the motivating schema rather than approximating it: a
 * {@code SECURITY DEFINER} routine ({@code create_secure_note}, in the catalog via init.sql so
 * {@code @routine} and {@code @table} resolve it), so the write runs as the function owner and
 * succeeds, inserting a row whose owner column does not match the caller's mounted identity,
 * under a policy that hides it. The caller commits a real row it cannot read.
 *
 * <p>RLS bypass note (inherited from {@code SessionHookExecutionTest}): PostgreSQL superusers
 * bypass RLS outright, and the pooled test connection is the container's superuser, so the
 * generated fetcher must run over a connection opened as a dedicated non-superuser role
 * (the per-execution {@code DSLContext} handed to {@code Graphitron.newExecutionInput}, the
 * {@code TenantDivinedRoutingExecutionTest} per-test connection-config precedent).
 */
@ExecutionTier
class RoutineCarrierRlsExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext superDsl;            // superuser: out-of-band setup, assertions, cleanup
    static String jdbcUrl;
    static GraphQL graphql;
    static final String PROBE_USER = "carrier_rls_probe";
    static final String PROBE_PASSWORD = "probe";

    @BeforeAll
    static void startDatabase() {
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
        superDsl = DSL.using(jdbcUrl, user, password);

        // The table, routine and policy live in init.sql (the catalog needs them); the
        // non-superuser role and its grants are this test's own concern. EXECUTE on the
        // SECURITY DEFINER routine lets the probe write as the owner; SELECT lets the probe's
        // post-commit re-read run — and return nothing, because the policy scopes it.
        // DROP OWNED first: the grants on the persistent catalog table would otherwise block
        // the role drop (a leftover role from an aborted run does the same on a local DB).
        dropProbeRole();
        superDsl.execute("create role " + PROBE_USER + " login password '" + PROBE_PASSWORD + "'");
        superDsl.execute("grant select on secure_note to " + PROBE_USER);
        superDsl.execute("grant execute on function create_secure_note(text, text) to " + PROBE_USER);

        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (superDsl != null) {
            superDsl.execute("delete from secure_note");
            dropProbeRole();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Drops the probe role after revoking what it holds ({@code DROP OWNED} revokes every
     * privilege granted to the role): the grants target the persistent catalog table, so a
     * bare {@code DROP ROLE} would fail on the dependency.
     */
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
    @SuppressWarnings("unchecked")
    void secureNoteWrite_commitsARowTheCallerCannotRead_dataNullErrorsEmptyNoFieldError() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, PROBE_USER, PROBE_PASSWORD)) {
            try (var st = conn.createStatement()) {
                st.execute("set app.user_id = 'alice'");
            }
            DSLContext probeDsl = DSL.using(conn, SQLDialect.POSTGRES);

            var input = Graphitron.newExecutionInput(probeDsl, "alice")
                .query("""
                    mutation {
                        createSecureNote(owner: "pending-grant", note: "invisible to alice") {
                            secureNote { noteId note }
                            errors { __typename }
                        }
                    }
                    """)
                .build();
            var result = graphql.execute(input);

            // Outcome (b) is a success shape: no field error, no sentinel, no channel dispatch.
            assertThat(result.getErrors())
                .as("top-level errors: %s", result.getErrors())
                .isEmpty();
            Map<String, Object> data = result.getData();
            var payload = (Map<String, Object>) data.get("createSecureNote");
            assertThat(payload)
                .as("the payload itself is non-null: step 1 captured a real key")
                .isNotNull();
            assertThat(payload.get("secureNote"))
                .as("the data field resolves null — the read policy hides the fresh row")
                .isNull();
            assertThat(payload.get("errors"))
                .as("the errors channel is empty — nothing raised, nothing dispatched")
                .isNull();

            // The write really committed: the superuser (who bypasses RLS) sees the row...
            assertThat(superDsl.fetchCount(DSL.table("secure_note"),
                    DSL.field("note", String.class).eq("invisible to alice")))
                .as("the SECURITY DEFINER write committed the row")
                .isEqualTo(1);
            // ...while the caller's own connection genuinely cannot.
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("select count(*) from secure_note")) {
                rs.next();
                assertThat(rs.getInt(1))
                    .as("the probe role's mounted identity sees no rows")
                    .isZero();
            }
        } finally {
            superDsl.execute("delete from secure_note");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void secureNoteWrite_ownerMatchingMountedIdentity_dataFieldReReadsTheRow() throws Exception {
        // The visibility control: the same write with an owner the caller's identity CAN read
        // proves the null above is the policy's doing, not a broken re-read.
        try (Connection conn = DriverManager.getConnection(jdbcUrl, PROBE_USER, PROBE_PASSWORD)) {
            try (var st = conn.createStatement()) {
                st.execute("set app.user_id = 'alice'");
            }
            DSLContext probeDsl = DSL.using(conn, SQLDialect.POSTGRES);

            var input = Graphitron.newExecutionInput(probeDsl, "alice")
                .query("""
                    mutation {
                        createSecureNote(owner: "alice", note: "visible to alice") {
                            secureNote { note ownerId }
                            errors { __typename }
                        }
                    }
                    """)
                .build();
            var result = graphql.execute(input);

            assertThat(result.getErrors())
                .as("top-level errors: %s", result.getErrors())
                .isEmpty();
            Map<String, Object> data = result.getData();
            var payload = (Map<String, Object>) data.get("createSecureNote");
            var note = (Map<String, Object>) payload.get("secureNote");
            assertThat(note)
                .as("an owner-matching row is visible to the post-commit re-read")
                .isNotNull();
            assertThat(note.get("note")).isEqualTo("visible to alice");
            assertThat(note.get("ownerId")).isEqualTo("alice");
            assertThat(payload.get("errors")).isNull();
        } finally {
            superDsl.execute("delete from secure_note");
        }
    }
}
