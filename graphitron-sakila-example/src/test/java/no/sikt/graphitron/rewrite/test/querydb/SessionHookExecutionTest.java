package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.udt.records.SessionClaimsRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.udt.records.SessionHandleRecord;
import no.sikt.graphitron.generated.schema.SessionMountException;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    static DSLContext dsl;                 // superuser, out-of-band setup and cleanup (bypasses RLS)
    static String jdbcUrl;
    static final String PROBE_USER = "rls_probe_user";
    static final String PROBE_PASSWORD = "probe";

    @BeforeAll
    static void startDatabase() throws SQLException {
        jdbcUrl = SakilaDatabase.url();
        String user = SakilaDatabase.user();
        String password = SakilaDatabase.password();

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
        exec("grant select on customer, store to " + PROBE_USER);
        exec("insert into rls_probe values (1,'alice','a1'),(2,'bob','b1'),(3,'alice','a2')");
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            exec("drop table if exists rls_probe");
            dropProbeRole();
        }
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
                .as("the mount's own exception propagates as the cause of the request-scoped rejection")
                .isInstanceOf(SessionMountException.class)
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
    void failedAutocommitAssertion_isNotARejectedIdentity() throws Exception {
        // A connection that refuses setAutoCommit is a deployment fault, not the database
        // rejecting the caller: it must not read as SessionMountException to a consumer that
        // maps that type to 401.
        Connection raw = probeConnection();
        raw.close();
        Executor sameThread = Runnable::run;
        assertThatThrownBy(() -> no.sikt.graphitron.generated.schema.PinnedConnection.acquire(
                singleConnectionDataSource(raw), SQLDialect.POSTGRES, new Settings(), sameThread,
                "{\"sub\":\"alice\"}"))
            .isNotInstanceOf(SessionMountException.class)
            .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void rejectedMount_rejectsTheWholeRequestOnce_withTheMountsCauseReachable() {
        // Three root fields that each need the database: one mount attempt, one evicted
        // connection, no per-field log line, and GraphQL.execute throws instead of answering.
        var opened = new CopyOnWriteArrayList<Connection>();
        var logged = new CopyOnWriteArrayList<Throwable>();
        var runtime = no.sikt.graphitron.generated.Graphitron.runtime(countingDataSource(opened), SQLDialect.POSTGRES)
            .withFetcherErrorLog((thrown, reference, env) -> logged.add(thrown));
        var engine = runtime.newGraphQL(no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {})).build();

        assertThatThrownBy(() -> engine.execute(rejectedInput(
                "{ a: filmsOrdered { title } b: customerByPk(customerId: 1) { firstName } c: sessionPrincipal }")))
            .isInstanceOf(SessionMountException.class)
            .hasStackTraceContaining("unentitled principal");
        assertThat(opened).as("the first rejection is remembered: one mount attempt, not one per field").hasSize(1);
        assertThat(opened).allSatisfy(c -> assertThat(c.isClosed()).as("evicted").isTrue());
        assertThat(logged).as("a rejected identity is no fetcher fault: nothing is logged per field").isEmpty();
    }

    @Test
    void rejectedMount_failsTheAsyncExecutionWithTheSameException() {
        var runtime = no.sikt.graphitron.generated.Graphitron.runtime(
            countingDataSource(new CopyOnWriteArrayList<>()), SQLDialect.POSTGRES);
        var engine = runtime.newGraphQL(no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {})).build();

        assertThat(engine.executeAsync(rejectedInput("{ filmsOrdered { title } }")))
            .failsWithin(java.time.Duration.ofSeconds(30))
            .withThrowableOfType(java.util.concurrent.ExecutionException.class)
            .withCauseInstanceOf(SessionMountException.class);
    }

    @Test
    void operationTouchingNoDatabase_neverMounts_soARejectedPrincipalStillSucceeds() {
        var opened = new CopyOnWriteArrayList<Connection>();
        var runtime = no.sikt.graphitron.generated.Graphitron.runtime(countingDataSource(opened), SQLDialect.POSTGRES);
        var engine = runtime.newGraphQL(no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {})).build();

        var result = engine.execute(rejectedInput("{ __typename }"));

        assertThat(result.getErrors()).isEmpty();
        assertThat(opened).as("lazy: no field needed a connection").isEmpty();
    }

    @Test
    void chainedInstrumentation_stillLetsTheRejectionEscape_inEitherOrder() {
        // Pins the graphql-java premise the override rests on: a throw from
        // instrumentExecutionResult escapes execute, also from inside a ChainedInstrumentation,
        // whichever side of the consumer's own instrumentation graphitron's sits.
        var runtime = no.sikt.graphitron.generated.Graphitron.runtime(
            countingDataSource(new CopyOnWriteArrayList<>()), SQLDialect.POSTGRES);
        var schema = no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {});
        graphql.execution.instrumentation.Instrumentation consumers =
            new graphql.execution.instrumentation.SimplePerformantInstrumentation();
        var graphitrons = new no.sikt.graphitron.generated.schema.GraphitronConnectionInstrumentation(runtime);
        for (var chain : List.of(List.of(graphitrons, consumers), List.of(consumers, graphitrons))) {
            var engine = graphql.GraphQL.newGraphQL(schema)
                .instrumentation(new graphql.execution.instrumentation.ChainedInstrumentation(chain))
                .build();
            assertThatThrownBy(() -> engine.execute(rejectedInput("{ filmsOrdered { title } }")))
                .isInstanceOf(SessionMountException.class);
        }
    }

    @Test
    void errorChannelMapping_neverClaimsARejectedMount() {
        // A GENERIC handler on RuntimeException, or a database mapping on the SQLState, would
        // otherwise turn a rejected identity into payload data.
        var claimsEverything = new no.sikt.graphitron.generated.schema.ErrorRouter.Mapping() {
            @Override public boolean match(Throwable thrown, Throwable candidate) { return true; }
            @Override public String description() { return null; }
        };
        var rejected = new SessionMountException(new SQLException("unentitled principal", "P0001"));

        assertThat(no.sikt.graphitron.generated.schema.ErrorRouter.claim(
                new java.util.concurrent.CompletionException(rejected),
                new no.sikt.graphitron.generated.schema.ErrorRouter.Mapping[]{claimsEverything}, null))
            .isNull();
        assertThat(no.sikt.graphitron.generated.schema.ErrorRouter.claim(
                new IllegalStateException("an ordinary fault"),
                new no.sikt.graphitron.generated.schema.ErrorRouter.Mapping[]{claimsEverything}, null))
            .as("control: the same mapping claims anything else")
            .isNotNull();
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
    void runtimeFetcherErrorLog_receivesTheUnmatchedThrowWithTheClientsReference() throws Exception {
        // The managed path's wiring: the runtime holds the log and the instrumentation publishes
        // it per operation, with nothing on the request. The probe role holds no grant on film, so
        // the UPDATE fails with a permission error: as unmatched as any, and a payload without an
        // error channel redacts it.
        var references = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var runtime = no.sikt.graphitron.generated.Graphitron.runtime(
                singleConnectionDataSource(probeConnection()), SQLDialect.POSTGRES)
            .withFetcherErrorLog((thrown, reference, env) -> references.add(reference));
        var engine = runtime.newGraphQL(
            no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {})).build();

        var result = engine.execute(no.sikt.graphitron.generated.Graphitron
            .newOwnedExecutionInput("{\"sub\":\"alice\"}", "alice")
            .query("mutation { updateFilmsPayload(in: [{ filmId: 2147483000, title: \"x\" }]) { films { filmId } } }")
            .build());

        assertThat(result.getErrors()).hasSize(1);
        String message = result.getErrors().get(0).getMessage();
        assertThat(message).matches("An error occurred\\. Reference: [0-9a-f-]{36}\\.");
        assertThat(references).containsExactly(
            message.substring("An error occurred. Reference: ".length(), message.length() - 1));
    }

    @Test
    void runtimeFetcherErrorLog_thatThrows_stillLeavesTheClientTheRedactedError() throws Exception {
        // An Error, not an Exception, so the guard's catch must be wide enough: anything escaping
        // the redact path would reach graphql-java's default handler, which puts its message in
        // the response.
        var runtime = no.sikt.graphitron.generated.Graphitron.runtime(
                singleConnectionDataSource(probeConnection()), SQLDialect.POSTGRES)
            .withFetcherErrorLog((thrown, reference, env) -> {
                throw new AssertionError("the consumer's log is broken");
            });
        var engine = runtime.newGraphQL(
            no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {})).build();

        var result = engine.execute(no.sikt.graphitron.generated.Graphitron
            .newOwnedExecutionInput("{\"sub\":\"alice\"}", "alice")
            .query("mutation { updateFilmsPayload(in: [{ filmId: 2147483000, title: \"x\" }]) { films { filmId } } }")
            .build());

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessage())
            .matches("An error occurred\\. Reference: [0-9a-f-]{36}\\.");
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

    @Test
    void sessionMemberBoundToAService_readsThatMemberOfTheMountedHandle() throws Exception {
        // $session.sessionNo: the member's own Integer binds where the whole handle did.
        var engine = ownedEngine();
        var result = engine.execute(no.sikt.graphitron.generated.Graphitron
            .newOwnedExecutionInput("{\"sub\":\"alice\"}", "alice")
            .query("{ sessionNumber sessionPrincipal }")
            .build());

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        java.util.Map<String, Object> data = result.getData();
        assertThat((Integer) data.get("sessionNumber")).isPositive();
        assertThat((String) data.get("sessionPrincipal"))
            .as("the member and the whole handle are the one mounted handle")
            .isEqualTo("alice#" + data.get("sessionNumber"));
    }

    @Test
    void sessionMemberReadByARootCondition_filtersByTheMountedPrincipal() throws Exception {
        var engine = ownedEngine();

        var mary = engine.execute(no.sikt.graphitron.generated.Graphitron
            .newOwnedExecutionInput("{\"sub\":\"mary\"}", "mary")
            .query("{ customersOfPrincipal { firstName } }")
            .build());
        assertThat(mary.getErrors()).as("errors: " + mary.getErrors()).isEmpty();
        assertThat(firstNames(mary.getData(), "customersOfPrincipal"))
            .as("the condition read the principal off the connection it runs on")
            .isNotEmpty().allMatch(name -> name.equalsIgnoreCase("mary"));

        // A fresh engine: the single-connection data source above is released by the first request.
        var nobody = ownedEngine().execute(no.sikt.graphitron.generated.Graphitron
            .newOwnedExecutionInput("{\"sub\":\"no-such-customer\"}", "no-such-customer")
            .query("{ customersOfPrincipal { firstName } }")
            .build());
        assertThat(nobody.getErrors()).isEmpty();
        assertThat(firstNames(nobody.getData(), "customersOfPrincipal")).isEmpty();
    }

    @Test
    void sessionMemberReadByAChildCondition_isProjectedIntoTheParentStatement() throws Exception {
        // The condition sits on an inline child, so its glue is called from the parent's $project
        // inside a correlated multiset: the projection unit takes dsl and the root launcher hands
        // it over, which is what makes the read per connection rather than per request.
        var engine = ownedEngine();
        var result = engine.execute(no.sikt.graphitron.generated.Graphitron
            .newOwnedExecutionInput("{\"sub\":\"mary\"}", "mary")
            .query("{ sessionStores { storeId customersOfPrincipal { firstName } } }")
            .build());

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        java.util.Map<String, Object> data = result.getData();
        @SuppressWarnings("unchecked")
        var nodes = (List<java.util.Map<String, Object>>) data.get("sessionStores");
        assertThat(nodes).isNotEmpty();
        var names = new java.util.ArrayList<String>();
        for (var node : nodes) {
            @SuppressWarnings("unchecked")
            var customers = (List<java.util.Map<String, Object>>) node.get("customersOfPrincipal");
            customers.forEach(c -> names.add((String) c.get("firstName")));
        }
        assertThat(names).isNotEmpty().allMatch(name -> name.equalsIgnoreCase("mary"));
    }

    @Test
    void sessionMemberReadByAConditionOnAnEscapeHatchOperation_failsLoudlyInsteadOfBindingNull() throws Exception {
        try (Connection conn = probeConnection()) {
            DSLContext supplied = DSL.using(conn, SQLDialect.POSTGRES);
            var engine = no.sikt.graphitron.generated.Graphitron.newGraphQL().build();
            var result = engine.execute(no.sikt.graphitron.generated.Graphitron
                .newExecutionInput(supplied, "{\"sub\":\"mary\"}", "mary")
                .query("{ customersOfPrincipal { firstName } }")
                .build());

            assertThat(result.getErrors())
                .as("an escape-hatch $session read in a condition is an error, not a silent empty filter")
                .isNotEmpty();
        }
    }

    // ===== helpers =====

    private static graphql.GraphQL ownedEngine() throws Exception {
        var runtime = no.sikt.graphitron.generated.Graphitron.runtime(
            singleConnectionDataSource(probeConnection()), SQLDialect.POSTGRES);
        return runtime.newGraphQL(no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {})).build();
    }

    private static List<String> firstNames(java.util.Map<String, Object> data, String field) {
        @SuppressWarnings("unchecked")
        var rows = (List<java.util.Map<String, Object>>) data.get(field);
        return rows.stream().map(r -> (String) r.get("firstName")).toList();
    }

    private static graphql.ExecutionInput rejectedInput(String query) {
        return no.sikt.graphitron.generated.Graphitron
            .newOwnedExecutionInput("{\"sub\":\"reject-me\"}", "reject-me")
            .query(query)
            .build();
    }

    /** A DataSource opening a fresh probe-role connection per call, recording each one. */
    private static javax.sql.DataSource countingDataSource(List<Connection> opened) {
        return new javax.sql.DataSource() {
            @Override public Connection getConnection() throws SQLException {
                var connection = probeConnection();
                opened.add(connection);
                return connection;
            }
            @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private static Connection probeConnection() throws SQLException {
        return SakilaDatabase.connect(jdbcUrl, PROBE_USER, PROBE_PASSWORD);
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
