package no.sikt.graphitron.rewrite.generators.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import graphql.ExecutionResult;
import no.sikt.graphitron.emit.SessionIdentityRows;
import no.sikt.graphitron.rewrite.session.SessionIdentityFixtures;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.lang.model.element.Modifier;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier behavioural coverage of {@link ConnectionRuntimeClassGenerator}'s lifecycle contract:
 * the emitted {@code PinnedConnection} wrapping a lease from the ready-made
 * {@code ConnectionSources.mounting} source. Because the runtime is emitted (never shipped as a
 * graphitron artifact), the only honest way to assert acquire/mount/unmount/release
 * <em>ordering</em> over a fake {@code DataSource} is to compile the real emitted {@code TypeSpec}s
 * and drive them: {@link EmittedCodeHarness} compiles them once, and every assertion reads the
 * interleaved call log recorded by the fake {@code DataSource}/{@code Connection} proxies and the
 * {@link LifecycleHookFixture} the {@link RecordingSource} callbacks call, never the emitted
 * source text.
 *
 * <p>The autocommit facts asserted here: {@code mounting} forces the transaction mode before the
 * mount (rolling back first because switching autocommit on commits an open transaction), so the
 * mount is its own committed transaction; {@code PinnedConnection} refuses a lease in autocommit
 * off, and settles an open transaction at release before the lease's release runs.
 */
@UnitTier
class ConnectionRuntimeClassGeneratorTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> pinnedConnectionClass;
    private static Class<?> runtimeClass;

    /** Ordered log of observable lifecycle events, shared by all fakes and the hook fixture. */
    private final List<String> events = new ArrayList<>();

    @BeforeAll
    static void compileEmittedRuntime() {
        // The runtime's newGraphQL(schema) references the connection instrumentation, which in
        // turn references the transaction provider and the carrier: the emitted runtime classes
        // are one connected cluster, so they compile together. This test still only drives the
        // connection-lifecycle classes below.
        harness = compile(SessionIdentityFixtures.recording());
        pinnedConnectionClass = harness.load(SCHEMA_PACKAGE + ".PinnedConnection");
        runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
    }

    private static EmittedCodeHarness compile(SessionIdentityRows hooks) {
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, null, hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        // The instrumentation reports a mutation field's settle failure through the router, which
        // raises the client-error type; a real build always emits both beside the runtime.
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.generate()) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        return EmittedCodeHarness.compile(units);
    }

    @AfterAll
    static void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @BeforeEach
    void resetEvents() {
        events.clear();
        arrivesInAutocommit = false;
        rollbackThrows = null;
        valid = true;
        product = "PostgreSQL";
        LifecycleHookFixture.reset(events);
    }

    @Test
    void acquireThenRelease_success_mountsThenUnmountsInOrder() throws Throwable {
        Object pinned = acquire(fakeDataSource());
        release(pinned);

        assertThat(events).containsExactly(
            "getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1", "disconnect:H1", "reset", "close");
    }

    @Test
    void mountFailure_evictsAndFailsClosedBeforeAnySql() {
        LifecycleHookFixture.mountThrows = new RuntimeException("unentitled role");

        assertThatThrownBy(() -> acquire(fakeDataSource()))
            .as("a throwing mount becomes the request-scoped rejection, the mount's throw as its cause")
            .satisfies(t -> assertThat(t.getClass().getSimpleName()).isEqualTo("AcquisitionFailedException"))
            .hasCause(LifecycleHookFixture.mountThrows);

        // Connection acquired, mount attempted and failed, connection evicted (aborted) rather
        // than returned; unmount never runs and no connection is handed back for SQL.
        assertThat(events).containsExactly("getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1", "abort");
    }

    @Test
    void unmountFailure_evictsInsteadOfReturningToPool() throws Throwable {
        LifecycleHookFixture.unmountThrows = new RuntimeException("unmount failed");

        Object pinned = acquire(fakeDataSource());
        assertThatThrownBy(() -> release(pinned))
            .as("a throwing unmount surfaces the failure")
            .isInstanceOf(Throwable.class);

        // Unmount attempted and failed, so the physical connection is aborted, never close()d
        // back into the pool, and the reset never runs on it. Whether the connection is still valid
        // is never asked: an unmount failure is rethrown whatever the connection's state.
        assertThat(events).containsExactly(
            "getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1", "disconnect:H1", "abort");
    }

    @Test
    void release_isIdempotent_cancelThenCompleteUnmountsExactlyOnce() throws Throwable {
        Object pinned = acquire(fakeDataSource());
        release(pinned); // e.g. cancellation signal
        release(pinned); // e.g. completion signal

        assertThat(events).containsExactly(
            "getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1", "disconnect:H1", "reset", "close");
    }

    @Test
    void release_isOutcomeAgnostic_unmountFiresOnEveryCompletionPath() throws Throwable {
        // Success path: the operation completed and the caller releases.
        assertThat(driveRelease(() -> { /* normal completion */ })).containsExactly(
            "getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1", "disconnect:H1", "reset", "close");

        // Error path: the operation threw; the caller still releases in its finally.
        resetEvents();
        assertThat(driveRelease(() -> { throw new RuntimeException("operation failed"); }))
            .containsExactly(
                "getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1", "disconnect:H1", "reset", "close");
    }

    @Test
    void plainSource_leasesThePoolsConnectionAndClosesIt_mountingNothing() throws Throwable {
        // ConnectionSources.of sets no session state and clears none: the lease's release returns the
        // connection to the pool, and PinnedConnection asserts autocommit without forcing it.
        arrivesInAutocommit = true;
        try (var plain = compile(SessionIdentityRows.empty())) {
            Object pinned = acquirePlain(plain);
            Accessible.method(plain.load(SCHEMA_PACKAGE + ".PinnedConnection"), "release").invoke(pinned);

            assertThat(events).containsExactly("getConnection", "close");
        }
    }

    @Test
    void plainSource_leasingAConnectionInAutocommitOff_isRefusedAsAFailedAcquisition_andClosed() throws Throwable {
        // The pool lent the connection with autocommit off; ConnectionSources.of does not force it,
        // so PinnedConnection refuses the lease rather than roll back what it cannot see.
        try (var plain = compile(SessionIdentityRows.empty())) {
            assertThatThrownBy(() -> acquirePlain(plain))
                .satisfies(t -> assertThat(t.getClass().getSimpleName()).isEqualTo("AcquisitionFailedException"))
                .hasRootCauseMessage("the connection source leased a connection with autocommit off");

            assertThat(events).containsExactly("getConnection", "close");
        }
    }

    private Object acquirePlain(EmittedCodeHarness plain) throws Throwable {
        DataSource dataSource = fakeDataSource();
        Class<?> plainRuntime = plain.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        Class<?> keyClass = plain.load(SCHEMA_PACKAGE + ".ConnectionKey");
        Class<?> sourceClass = plain.load(SCHEMA_PACKAGE + ".ConnectionSource");
        Object runtime = plainRuntime.getConstructor(SQLDialect.class).newInstance(SQLDialect.POSTGRES);
        Object source = RecordingSource.plain(plain, SCHEMA_PACKAGE, Map.of(RecordingSource.DEFAULT, dataSource));
        try {
            return Accessible.method(plainRuntime, "acquire", keyClass, sourceClass)
                .invoke(runtime, keyClass.getMethod("defaultSource").invoke(null), source);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ===== graphitron asserts the connection's transaction mode =====

    @Test
    void acquire_assertsAutocommit_beforeTheMountRuns() throws Throwable {
        // The fake DataSource hands out connections with autocommit=false (a pool can be
        // configured that way); acquire must force the mode before the mount, rolling back first
        // so nothing the pool left open is committed by the switch, or on Postgres
        // the mount's session-scoped state would sit in an implicit never-committed transaction
        // and revert with a later rollback, leaving the operation running unmounted under RLS.
        acquire(fakeDataSource());

        assertThat(events)
            .as("after pinning, the pool's open work is rolled back and autocommit forced, then the "
                + "settings captured, before the mount")
            .startsWith("getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1");
    }

    @Test
    void acquire_connectionArrivingInAutocommit_isNotRolledBack() throws Throwable {
        // The rollback is for a pool that lent the connection with autocommit off, where a
        // transaction may be open; a connection already in autocommit has none to discard.
        arrivesInAutocommit = true;

        release(acquire(fakeDataSource()));

        assertThat(events).containsExactly(
            "getConnection", "setAutoCommit:true", "capture", "connect->H1", "disconnect:H1", "reset", "close");
    }

    @Test
    void acquire_rollbackFailure_evictsBeforeTheMount() {
        // A refused rollback of what the pool left open evicts the connection before any mount, and
        // fails the acquisition with the rollback's own exception as the cause.
        rollbackThrows = new java.sql.SQLException("connection is broken", "08006");

        assertThatThrownBy(() -> acquire(fakeDataSource()))
            .as("a refused rollback at acquire fails the acquisition, its SQLState kept on the cause")
            .satisfies(t -> assertThat(t.getClass().getSimpleName()).isEqualTo("AcquisitionFailedException"))
            .hasCause(rollbackThrows);
        assertThat(events).containsExactly("getConnection", "abort");
    }

    @Test
    void release_operationLeftTransactionOpen_settlesBeforeTheUnmountRuns() throws Throwable {
        Object pinned = acquire(fakeDataSource());
        // Simulate an operation that died mid-mutation: the provider turned autocommit off and
        // never settled. Release must roll back and assert autocommit before the unmount (in
        // that order: switching autocommit on commits an open transaction), so the unmount's
        // clears commit immediately instead of being reverted by the pool's return-rollback.
        Connection connection = (Connection) Accessible.method(pinnedConnectionClass, "connection").invoke(pinned);
        connection.setAutoCommit(false);
        release(pinned);

        assertThat(events).containsExactly(
            "getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1",
            "setAutoCommit:false",                       // the simulated abandoned mutation transaction
            "rollback", "setAutoCommit:true",            // release settles before unmounting
            "disconnect:H1", "reset", "close");
    }

    @Test
    void release_rollbackFailingOnAConnectionNoLongerValid_evictsWithoutThrowing() throws Throwable {
        Object pinned = acquire(fakeDataSource());
        // The server ended the session over the operation's open transaction: the rollback before
        // the unmount fails, and the driver no longer reports the connection valid.
        Connection connection = (Connection) Accessible.method(pinnedConnectionClass, "connection").invoke(pinned);
        connection.setAutoCommit(false);
        events.clear();
        rollbackThrows = new java.sql.SQLException("This connection has been closed.", "08003");
        valid = false;
        var warnings = new ListAppender<ILoggingEvent>();
        warnings.start();
        var logger = (Logger) LoggerFactory.getLogger(SCHEMA_PACKAGE + ".PinnedConnection");
        logger.addAppender(warnings);
        try {
            release(pinned);
        } finally {
            logger.detachAppender(warnings);
        }

        assertThat(events)
            .as("asked whether the connection is still valid, then evicted; no unmount, no reset, never returned")
            .containsExactly("isValid", "abort");
        assertThat(warnings.list)
            .as("the failed rollback is logged as a warning, with the failure it was")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo(rollbackThrows.getMessage());
            });
    }

    @Test
    void release_rollbackFailingOnAConnectionStillValid_evictsAndRethrows() throws Throwable {
        Object pinned = acquire(fakeDataSource());
        Connection connection = (Connection) Accessible.method(pinnedConnectionClass, "connection").invoke(pinned);
        connection.setAutoCommit(false);
        events.clear();
        rollbackThrows = new java.sql.SQLException("could not roll back", "08006");

        assertThatThrownBy(() -> release(pinned))
            .as("a live connection that cannot be settled leaves identity unprovable: the failure surfaces")
            .hasCause(rollbackThrows);
        assertThat(events).containsExactly("isValid", "abort");
    }

    @Test
    void handle_isCarriedFromMountToRelease_andExposedForTheCarrierEntry() throws Throwable {
        Object pinned = acquire(fakeDataSource());

        assertThat(Accessible.method(pinnedConnectionClass, "handle").invoke(pinned))
            .as("the mount's returned handle is the pinned carrier's one retained field, "
                + "read by release and published on the carrier entry's Configuration")
            .isEqualTo("H1");
        release(pinned);
        assertThat(events).last().isEqualTo("close");
        assertThat(events).contains("disconnect:H1");
    }

    @Test
    void mounting_cannotBeBuiltWithoutAnUnmount() throws Throwable {
        Class<?> sources = harness.load(SCHEMA_PACKAGE + ".ConnectionSources");
        Class<?> mount = harness.load(SCHEMA_PACKAGE + ".ConnectionSources$Mount");
        Class<?> unmount = harness.load(SCHEMA_PACKAGE + ".ConnectionSources$Unmount");

        List<Method> factories = java.util.Arrays.stream(sources.getDeclaredMethods())
            .filter(m -> m.getName().equals("mounting") && java.lang.reflect.Modifier.isStatic(m.getModifiers()))
            .toList();
        assertThat(factories)
            .as("one factory, taking the pool and both callbacks: a mount-only source has no way to be built")
            .singleElement()
            .satisfies(of -> assertThat(of.getParameterTypes())
                .containsExactly(java.util.function.Function.class, mount, unmount));

        assertThatThrownBy(() -> RecordingSource.mounting(harness, SCHEMA_PACKAGE, key -> fakeDataSource(),
                (connection, key) -> "aHandle", null))
            .as("a null unmount is refused at construction, not discovered at release")
            .isInstanceOf(NullPointerException.class)
            .hasMessage("unmount");
    }

    // --- the PostgreSQL reset at release, the backstop to the unmount ---------------------------

    @Test
    void reset_restoresExactlyTheSettingsTheCaptureReadAtAcquire() throws Throwable {
        captureRows = List.of(
            new String[]{"default_transaction_isolation", "serializable"},
            new String[]{"search_path", "app, \"My Schema\", public"});

        release(acquire(fakeDataSource()));

        assertThat(resetBound)
            .as("names and values bound at release are what the capture returned at acquire, in order")
            .containsExactly(
                new Object[]{"default_transaction_isolation", "search_path"},
                new Object[]{"serializable", "app, \"My Schema\", public"});
    }

    @Test
    void reset_withNothingCaptured_bindsEmptyArraysAndStillResets() throws Throwable {
        release(acquire(fakeDataSource()));

        assertThat(events).containsSubsequence("disconnect:H1", "reset", "close");
        assertThat(resetBound).containsExactly(new Object[0], new Object[0]);
    }

    @Test
    void captureFailure_evictsBeforeTheMount_andFailsTheAcquisitionWithItAsTheCause() {
        captureThrows = new IllegalStateException("pg_settings unreadable");

        assertThatThrownBy(() -> acquire(fakeDataSource()))
            .as("a failed capture fails the acquisition, the capture's own throw as its cause")
            .satisfies(t -> assertThat(t.getClass().getSimpleName()).isEqualTo("AcquisitionFailedException"))
            .hasCause(captureThrows);
        assertThat(events).containsExactly("getConnection", "rollback", "setAutoCommit:true", "capture", "abort");
    }

    @Test
    void resetFailure_evictsInsteadOfReturningToPool() throws Throwable {
        resetThrows = new java.sql.SQLException("permission denied to set parameter", "42501");

        Object pinned = acquire(fakeDataSource());
        assertThatThrownBy(() -> release(pinned))
            .as("a failed reset surfaces, wrapped as the release's unchecked rethrow")
            .hasCause(resetThrows);
        assertThat(events).containsExactly(
            "getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1", "disconnect:H1", "reset", "abort");
    }

    @Test
    void unmountLeavingAutocommitOff_isEvictedWithoutAReset() throws Throwable {
        // RESET ALL is transactional: run inside the transaction the unmount left open, the pool's
        // return-rollback would revert it. The runtime evicts rather than settling for the callback.
        LifecycleHookFixture.unmountLeavesAutoCommitOff = true;

        Object pinned = acquire(fakeDataSource());
        assertThatThrownBy(() -> release(pinned))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("autocommit off");
        assertThat(events).containsExactly(
            "getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1",
            "disconnect:H1", "setAutoCommit:false", "abort");
    }

    @Test
    void nonPostgresConnection_neitherCapturesNorResets() throws Throwable {
        // mounting decides by the connection's own database, not by the runtime's dialect.
        product = "H2";
        Object pinned = acquire(fakeDataSource());
        release(pinned);

        assertThat(events).containsExactly(
            "getConnection", "rollback", "setAutoCommit:true", "connect->H1", "disconnect:H1", "close");
    }

    @Test
    void reset_runsWhenTheUnmountDoesNothing() throws Throwable {
        // The consumer's unmount is the clear; the reset backs it up whatever it does, including
        // nothing at all.
        DataSource dataSource = fakeDataSource();
        Object source = RecordingSource.mounting(harness, SCHEMA_PACKAGE, key -> dataSource,
            (connection, key) -> "H-noop", (connection, key, handle) -> { });

        release(acquire(source, SQLDialect.POSTGRES));

        assertThat(events).containsExactly("getConnection", "rollback", "setAutoCommit:true", "capture", "reset", "close");
    }

    // --- the fetcher-exception log and response -----------------------------------------------

    @Test
    void fetcherErrorLog_isAFunctionalInterfaceWithNoTenantArmInASingleTenantBuild() {
        TypeSpec log = unit(ConnectionRuntimeClassGenerator.generate(PACKAGE),
            ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);

        assertThat(log.kind()).isEqualTo(TypeSpec.Kind.INTERFACE);
        assertThat(log.annotations()).extracting(a -> a.type().toString())
            .containsExactly(FunctionalInterface.class.getName());
        assertThat(abstractMethodNames(log))
            .as("exactly one abstract method, so a later hook point can arrive as a default method")
            .containsExactly("unmatched");
        assertThat(log.methodSpecs()).extracting(MethodSpec::name).doesNotContain("tenantFailed");
        assertThat(parameterTypes(method(log, "unmatched"))).containsExactly(
            "java.lang.Throwable", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
        MethodSpec matched = method(log, "matched");
        assertThat(matched.modifiers()).contains(Modifier.DEFAULT);
        assertThat(parameterTypes(matched)).containsExactly(
            "java.lang.Throwable", "java.lang.Throwable", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void fetcherErrorResponse_hasFourDefaultMethodsAndNoAbstractOne() {
        TypeSpec response = unit(ConnectionRuntimeClassGenerator.generate(PACKAGE),
            ConnectionRuntimeClassGenerator.FETCHER_ERROR_RESPONSE_CLASS_NAME);

        assertThat(response.kind()).isEqualTo(TypeSpec.Kind.INTERFACE);
        assertThat(response.annotations()).as("not a functional interface: a consumer overrides what it needs")
            .isEmpty();
        assertThat(abstractMethodNames(response)).isEmpty();
        assertThat(response.methodSpecs()).extracting(MethodSpec::name)
            .containsExactly("reference", "matchedMessage", "clientErrorMessage", "unmatchedMessage");
        assertThat(response.methodSpecs()).allSatisfy(m -> {
            assertThat(m.modifiers()).contains(Modifier.DEFAULT);
            assertThat(m.returnType().toString()).isEqualTo("java.lang.String");
        });
        assertThat(parameterTypes(method(response, "reference"))).containsExactly(
            "java.lang.Throwable", "graphql.schema.DataFetchingEnvironment");
        assertThat(parameterTypes(method(response, "matchedMessage"))).containsExactly(
            "java.lang.Throwable", "java.lang.String", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
        assertThat(parameterTypes(method(response, "clientErrorMessage"))).containsExactly(
            SCHEMA_PACKAGE + ".GraphitronClientException", "graphql.schema.DataFetchingEnvironment");
        assertThat(parameterTypes(method(response, "unmatchedMessage"))).containsExactly(
            "java.lang.Throwable", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void fetcherErrorLog_tenantFailedIsADefaultTypedWithTheTenantKey() {
        TypeSpec log = unit(
            ConnectionRuntimeClassGenerator.generate(PACKAGE, SessionIdentityRows.empty(),
                ClassName.get(Integer.class), TenantColumnFixtures.INTEGER_KEY_COLUMN),
            ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);

        assertThat(abstractMethodNames(log)).containsExactly("unmatched");
        MethodSpec tenantFailed = method(log, "tenantFailed");
        assertThat(tenantFailed.modifiers()).contains(Modifier.DEFAULT);
        assertThat(parameterTypes(tenantFailed)).containsExactly(
            "java.lang.Integer", "java.lang.Throwable", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
    }

    // --- the engine's instrumentation chain, the Settings form, and the mount's own exception --------

    private static graphql.schema.GraphQLSchema trivialSchema() {
        return new graphql.schema.idl.SchemaGenerator().makeExecutableSchema(
            new graphql.schema.idl.SchemaParser().parse("type Query { x: String }"),
            graphql.schema.idl.RuntimeWiring.newRuntimeWiring().build());
    }

    private Object newRuntime() throws Throwable {
        return runtimeClass.getConstructor(SQLDialect.class).newInstance(SQLDialect.POSTGRES);
    }

    private static List<graphql.execution.instrumentation.Instrumentation> chainOf(graphql.GraphQL engine) {
        assertThat(engine.getInstrumentation()).isInstanceOf(graphql.execution.instrumentation.ChainedInstrumentation.class);
        return ((graphql.execution.instrumentation.ChainedInstrumentation) engine.getInstrumentation())
            .getInstrumentations();
    }

    @Test
    void newGraphQL_withInstrumentations_keepsGraphitronsFirstAndTheGivenOnesAfterItInOrder() throws Throwable {
        var first = new graphql.execution.instrumentation.SimplePerformantInstrumentation();
        var second = new graphql.execution.instrumentation.SimplePerformantInstrumentation();
        Method newGraphQL = runtimeClass.getMethod("newGraphQL", graphql.schema.GraphQLSchema.class,
            graphql.execution.instrumentation.Instrumentation[].class);
        assertThat(newGraphQL.isVarArgs()).isTrue();

        var builder = (graphql.GraphQL.Builder) newGraphQL.invoke(newRuntime(), trivialSchema(),
            new graphql.execution.instrumentation.Instrumentation[] {first, second});
        var chain = chainOf(builder.build());

        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).getClass().getName())
            .isEqualTo(SCHEMA_PACKAGE + ".GraphitronConnectionInstrumentation");
        assertThat(chain.subList(1, 3)).containsExactly(first, second);
    }

    @Test
    void newGraphQL_withNoInstrumentations_isTheConnectionInstrumentationAlone() throws Throwable {
        Method newGraphQL = runtimeClass.getMethod("newGraphQL", graphql.schema.GraphQLSchema.class,
            graphql.execution.instrumentation.Instrumentation[].class);
        var builder = (graphql.GraphQL.Builder) newGraphQL.invoke(newRuntime(), trivialSchema(),
            new graphql.execution.instrumentation.Instrumentation[0]);

        assertThat(chainOf(builder.build())).extracting(i -> i.getClass().getName())
            .containsExactly(SCHEMA_PACKAGE + ".GraphitronConnectionInstrumentation");
    }

    @Test
    void newGraphQL_refusesANullInstrumentation() throws Throwable {
        Method newGraphQL = runtimeClass.getMethod("newGraphQL", graphql.schema.GraphQLSchema.class,
            graphql.execution.instrumentation.Instrumentation[].class);
        Object runtime = newRuntime();
        assertThatThrownBy(() -> {
            try {
                newGraphQL.invoke(runtime, trivialSchema(),
                    new graphql.execution.instrumentation.Instrumentation[] {null});
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void runtime_settingsForm_hands_theSettingsToTheConnectionItAcquires() throws Throwable {
        Settings settings = new Settings().withRenderFormatted(true);
        Object runtime = runtimeClass.getConstructor(SQLDialect.class, Settings.class)
            .newInstance(SQLDialect.POSTGRES, settings);
        Class<?> keyClass = harness.load(SCHEMA_PACKAGE + ".ConnectionKey");
        Method acquire = Accessible.method(runtimeClass, "acquire", keyClass, harness.load(SCHEMA_PACKAGE + ".ConnectionSource"));

        Object pinned = acquire.invoke(runtime, keyClass.getMethod("defaultSource").invoke(null),
            RecordingSource.createLifecycle(harness, SCHEMA_PACKAGE, fakeDataSource()));
        try {
            assertThat(Accessible.method(pinned.getClass(), "settings").invoke(pinned)).isSameAs(settings);
            assertThat(Accessible.method(pinned.getClass(), "dialect").invoke(pinned)).isEqualTo(SQLDialect.POSTGRES);
        } finally {
            release(pinned);
        }
    }

    @Test
    void rejectionCause_isTheSourcesOwnExceptionWhereverAFailedAcquisitionIsOnTheChain() throws Throwable {
        assertAcquisitionCauseReadsTheChain(harness);
    }

    @Test
    void rejectionCause_readsTheChainTheSameWayWithoutSessionIdentity() throws Throwable {
        // AcquisitionFailedException is emitted in every build, session identity or not.
        try (var plain = compile(SessionIdentityRows.empty())) {
            assertAcquisitionCauseReadsTheChain(plain);
        }
    }

    private static void assertAcquisitionCauseReadsTheChain(EmittedCodeHarness h) throws Throwable {
        Class<?> ownedConnections = h.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        Class<?> failed = h.load(SCHEMA_PACKAGE + ".AcquisitionFailedException");
        Method acquisitionCause = ownedConnections.getMethod("rejectionCause", Throwable.class);
        Method isAcquisitionFailure = ownedConnections.getMethod("isRequestRejection", Throwable.class);

        var theSourcesOwn = new IllegalStateException("the routine said no");
        Throwable rejected = (Throwable) failed.getConstructor(Throwable.class).newInstance(theSourcesOwn);
        var wrapped = new java.util.concurrent.CompletionException(new RuntimeException("outer", rejected));

        assertThat(acquisitionCause.invoke(null, rejected)).isEqualTo(Optional.of(theSourcesOwn));
        assertThat(acquisitionCause.invoke(null, wrapped)).isEqualTo(Optional.of(theSourcesOwn));
        assertThat(isAcquisitionFailure.invoke(null, wrapped)).isEqualTo(true);

        var unrelated = new RuntimeException("not an acquisition", new IllegalStateException());
        assertThat(acquisitionCause.invoke(null, unrelated)).isEqualTo(Optional.empty());
        assertThat(isAcquisitionFailure.invoke(null, unrelated)).isEqualTo(false);
    }

    @Test
    void runtime_carriesTheLogThroughAWitherAndAnOptionalAccessor() {
        TypeSpec runtime = unit(ConnectionRuntimeClassGenerator.generate(PACKAGE),
            ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        var logType = ClassName.get(SCHEMA_PACKAGE, ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);

        MethodSpec wither = method(runtime, "withFetcherErrorLog");
        assertThat(wither.returnType()).isEqualTo(ClassName.get(SCHEMA_PACKAGE, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME));
        assertThat(wither.parameters()).extracting(ParameterSpec::type).containsExactly(logType);
        assertThat(method(runtime, "fetcherErrorLog").returnType())
            .isEqualTo(ParameterizedTypeName.get(ClassName.get(Optional.class), logType));

        var responseType = ClassName.get(SCHEMA_PACKAGE, ConnectionRuntimeClassGenerator.FETCHER_ERROR_RESPONSE_CLASS_NAME);
        assertThat(method(runtime, "withFetcherErrorResponse").parameters()).extracting(ParameterSpec::type)
            .containsExactly(responseType);
        assertThat(method(runtime, "fetcherErrorResponse").returnType())
            .isEqualTo(ParameterizedTypeName.get(ClassName.get(Optional.class), responseType));
    }

    @Test
    void batchSizeLimit_isAFunctionalInterfaceWithOneMethodAndNothingElse() {
        TypeSpec limit = unit(ConnectionRuntimeClassGenerator.generate(PACKAGE),
            ConnectionRuntimeClassGenerator.BATCH_SIZE_LIMIT_CLASS_NAME);

        assertThat(limit.kind()).isEqualTo(TypeSpec.Kind.INTERFACE);
        assertThat(limit.annotations()).extracting(a -> a.type().toString())
            .containsExactly(FunctionalInterface.class.getName());
        assertThat(limit.methodSpecs()).extracting(MethodSpec::name).containsExactly("maxBatchSize");
        MethodSpec maxBatchSize = method(limit, "maxBatchSize");
        assertThat(maxBatchSize.returnType().toString()).isEqualTo("int");
        assertThat(parameterTypes(maxBatchSize)).containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void runtime_carriesTheBatchSizeLimitThroughAWitherAndAnOptionalAccessor() {
        TypeSpec runtime = unit(ConnectionRuntimeClassGenerator.generate(PACKAGE),
            ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        var limitType = ClassName.get(SCHEMA_PACKAGE, ConnectionRuntimeClassGenerator.BATCH_SIZE_LIMIT_CLASS_NAME);

        MethodSpec wither = method(runtime, "withBatchSizeLimit");
        assertThat(wither.returnType()).isEqualTo(ClassName.get(SCHEMA_PACKAGE, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME));
        assertThat(wither.parameters()).extracting(ParameterSpec::type).containsExactly(limitType);
        assertThat(method(runtime, "batchSizeLimit").returnType())
            .isEqualTo(ParameterizedTypeName.get(ClassName.get(Optional.class), limitType));
    }

    @Test
    void withBatchSizeLimit_returnsACopyCarryingTheLimit_whichTheOtherWithersKeep() throws Throwable {
        Class<?> limitClass = harness.load(
            SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.BATCH_SIZE_LIMIT_CLASS_NAME);
        Class<?> logClass = harness.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);
        Class<?> responseClass = harness.load(
            SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.FETCHER_ERROR_RESPONSE_CLASS_NAME);
        Object limit = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{limitClass},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "recordingLimit"));
        Object log = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{logClass},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "recordingLog"));
        Object response = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{responseClass},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "recordingResponse"));
        Object original = runtimeClass.getConstructor(SQLDialect.class).newInstance(SQLDialect.POSTGRES);

        Object limited = runtimeClass.getMethod("withBatchSizeLimit", limitClass).invoke(original, limit);
        Object logged = runtimeClass.getMethod("withFetcherErrorLog", logClass).invoke(limited, log);
        Object all = runtimeClass.getMethod("withFetcherErrorResponse", responseClass).invoke(logged, response);
        Object relimited = runtimeClass.getMethod("withBatchSizeLimit", limitClass).invoke(all, limit);

        assertThat(runtimeClass.getMethod("batchSizeLimit").invoke(original)).isEqualTo(Optional.empty());
        assertThat(runtimeClass.getMethod("batchSizeLimit").invoke(limited)).isEqualTo(Optional.of(limit));
        assertThat(runtimeClass.getMethod("batchSizeLimit").invoke(all))
            .as("the log's and the response's withers carry the limit over").isEqualTo(Optional.of(limit));
        assertThat(runtimeClass.getMethod("fetcherErrorLog").invoke(relimited)).isEqualTo(Optional.of(log));
        assertThat(runtimeClass.getMethod("fetcherErrorResponse").invoke(relimited)).isEqualTo(Optional.of(response));
        assertThatThrownBy(() -> {
            try {
                runtimeClass.getMethod("withBatchSizeLimit", limitClass).invoke(original, (Object) null);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void withFetcherErrorResponse_keepsTheLog_andWithFetcherErrorLogKeepsTheResponse() throws Throwable {
        Class<?> logClass = harness.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);
        Class<?> responseClass = harness.load(
            SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.FETCHER_ERROR_RESPONSE_CLASS_NAME);
        Object log = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{logClass},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "recordingLog"));
        Object response = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{responseClass},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "recordingResponse"));
        Object original = runtimeClass.getConstructor(SQLDialect.class).newInstance(SQLDialect.POSTGRES);

        Object logged = runtimeClass.getMethod("withFetcherErrorLog", logClass).invoke(original, log);
        Object both = runtimeClass.getMethod("withFetcherErrorResponse", responseClass).invoke(logged, response);
        Object relogged = runtimeClass.getMethod("withFetcherErrorLog", logClass).invoke(both, log);

        assertThat(runtimeClass.getMethod("fetcherErrorLog").invoke(both)).isEqualTo(Optional.of(log));
        assertThat(runtimeClass.getMethod("fetcherErrorResponse").invoke(both)).isEqualTo(Optional.of(response));
        assertThat(runtimeClass.getMethod("fetcherErrorResponse").invoke(relogged)).isEqualTo(Optional.of(response));
        assertThat(runtimeClass.getMethod("fetcherErrorResponse").invoke(logged)).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> {
            try {
                runtimeClass.getMethod("withFetcherErrorResponse", responseClass).invoke(original, (Object) null);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void withFetcherErrorLog_returnsACopyCarryingTheLog_andLeavesTheOriginalWithout() throws Throwable {
        Class<?> logClass = harness.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);
        Object log = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{logClass},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "recordingLog"));
        Object original = runtimeClass.getConstructor(SQLDialect.class).newInstance(SQLDialect.POSTGRES);

        Object copy = runtimeClass.getMethod("withFetcherErrorLog", logClass).invoke(original, log);

        assertThat(runtimeClass.getMethod("fetcherErrorLog").invoke(copy)).isEqualTo(Optional.of(log));
        assertThat(runtimeClass.getMethod("fetcherErrorLog").invoke(original)).isEqualTo(Optional.empty());
        assertThat(runtimeClass.getMethod("dialect").invoke(copy)).isEqualTo(SQLDialect.POSTGRES);
        assertThatThrownBy(() -> {
            try {
                runtimeClass.getMethod("withFetcherErrorLog", logClass).invoke(original, (Object) null);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(NullPointerException.class);
    }

    private static TypeSpec unit(List<TypeSpec> units, String name) {
        return units.stream().filter(u -> u.name().equals(name)).findFirst().orElseThrow();
    }

    private static MethodSpec method(TypeSpec type, String name) {
        return type.methodSpecs().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> abstractMethodNames(TypeSpec type) {
        return type.methodSpecs().stream()
            .filter(m -> m.modifiers().contains(Modifier.ABSTRACT))
            .map(MethodSpec::name)
            .toList();
    }

    private static List<String> parameterTypes(MethodSpec method) {
        return method.parameters().stream().map(p -> p.type().toString()).toList();
    }

    // --- release on every way a request ends, driven through the engine ------------------------

    private static final List<String> ROOT_FIELDS = List.of("a", "b");

    /** One connection's whole life: pinned, captured, mounted, unmounted, reset, returned to the pool. */
    private static final List<String> PIN_AND_RELEASE = List.of(
        "getConnection", "rollback", "setAutoCommit:true", "capture", "connect->H1", "disconnect:H1", "reset", "close");

    private CompletableFuture<ExecutionResult> executeOverEngine(String operation, SynchronousAbortDriver.Abort abort)
            throws Throwable {
        Class<?> carrierClass = harness.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        Method dslDefault = carrierClass.getMethod("dslDefault");
        var engine = SynchronousAbortDriver.engine(newRuntime(), ROOT_FIELDS, carrierClass,
            field -> dslDefault::invoke, new SynchronousAbortDriver.AbortAt("b", abort));
        Map<Object, Object> context = new HashMap<>();
        context.put(SessionIdentityRows.CONTEXT_KEY, RecordingSource.createLifecycle(harness, SCHEMA_PACKAGE, fakeDataSource()));
        return SynchronousAbortDriver.execute(engine, operation, ROOT_FIELDS, context);
    }

    /** The pin, mount, unmount and return alone, without the mode switches a transaction adds. */
    private static final List<String> PINNED_MOUNTED_RELEASED = List.of(
        "getConnection", "capture", "connect->H1", "disconnect:H1", "reset", "close");

    private List<String> lifecycleEvents() {
        return events.stream()
            .filter(e -> !e.startsWith("setAutoCommit:") && !e.equals("commit") && !e.equals("rollback"))
            .toList();
    }

    /** Field {@code a} committed when {@code b} began, before its connection was unmounted. */
    private void assertFirstFieldCommittedBeforeRelease() {
        assertThat(events).containsOnlyOnce("commit");
        assertThat(events.indexOf("commit")).isLessThan(events.indexOf("disconnect:H1"));
    }

    @Test
    void engine_query_releasesThePinnedConnectionWhenItCompletes() throws Throwable {
        ExecutionResult result = executeOverEngine("query", SynchronousAbortDriver.Abort.NONE).get();

        assertThat(result.getErrors()).isEmpty();
        assertThat(events).containsExactlyElementsOf(PIN_AND_RELEASE);
    }

    @Test
    void engine_mutation_commitsTheLastFieldBeforeItsConnectionIsUnmountedAndClosed() throws Throwable {
        // Settle runs in the operation context and release in the execution context, so the order
        // rests on graphql-java completing the execution context after the operation context. Were
        // that to change, release would roll back a field that succeeded; this pins it.
        ExecutionResult result = executeOverEngine("mutation", SynchronousAbortDriver.Abort.NONE).get();

        assertThat(result.getErrors()).isEmpty();
        assertThat(events).containsSubsequence("commit", "commit", "disconnect:H1", "close");
        assertThat(events.lastIndexOf("commit")).isLessThan(events.indexOf("disconnect:H1"));
        assertThat(lifecycleEvents()).containsExactlyElementsOf(PINNED_MOUNTED_RELEASED);
    }

    @Test
    void engine_query_cancelledBeforeALaterRootFieldDispatches_stillReleasesTheEarlierFieldsConnection()
            throws Throwable {
        ExecutionResult result = executeOverEngine("query", SynchronousAbortDriver.Abort.CANCEL).get();

        assertThat(result.getErrors()).singleElement()
            .isInstanceOf(graphql.execution.AbortExecutionException.class);
        assertThat(events).containsExactlyElementsOf(PIN_AND_RELEASE);
    }

    @Test
    void engine_query_exceptionDuringDispatchOfALaterRootField_stillReleasesTheEarlierFieldsConnection()
            throws Throwable {
        var future = executeOverEngine("query", SynchronousAbortDriver.Abort.THROW);

        assertThatThrownBy(future::get)
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(SynchronousAbortDriver.InjectedFailure.class);
        assertThat(events).containsExactlyElementsOf(PIN_AND_RELEASE);
    }

    @Test
    void engine_mutation_cancelledBeforeALaterRootFieldDispatches_releasesTheEarlierFieldsConnection()
            throws Throwable {
        executeOverEngine("mutation", SynchronousAbortDriver.Abort.CANCEL).handle((r, t) -> null).get();

        assertThat(lifecycleEvents()).containsExactlyElementsOf(PINNED_MOUNTED_RELEASED);
        assertFirstFieldCommittedBeforeRelease();
    }

    @Test
    void engine_mutation_exceptionDuringDispatchOfALaterRootField_releasesTheEarlierFieldsConnection()
            throws Throwable {
        executeOverEngine("mutation", SynchronousAbortDriver.Abort.THROW).handle((r, t) -> null).get();

        assertThat(lifecycleEvents()).containsExactlyElementsOf(PINNED_MOUNTED_RELEASED);
        assertFirstFieldCommittedBeforeRelease();
    }

    // --- driving helpers -----------------------------------------------------------------------

    /** Runs acquire, then {@code body}, then release in a finally, capturing the event log. */
    private List<String> driveRelease(Runnable body) throws Throwable {
        Object pinned = acquire(fakeDataSource());
        try {
            body.run();
        } catch (RuntimeException ignored) {
            // the operation failed; release still must unmount
        } finally {
            release(pinned);
        }
        return events;
    }

    private Object acquire(DataSource dataSource) throws Throwable {
        return acquire(RecordingSource.createLifecycle(harness, SCHEMA_PACKAGE, dataSource), SQLDialect.POSTGRES);
    }

    /** {@code PinnedConnection.acquire} of the default key from {@code source}. */
    private Object acquire(Object source, SQLDialect dialect) throws Throwable {
        Executor sameThread = Runnable::run;
        Class<?> keyClass = harness.load(SCHEMA_PACKAGE + ".ConnectionKey");
        Method acquire = Accessible.method(pinnedConnectionClass,
            "acquire", harness.load(SCHEMA_PACKAGE + ".ConnectionSource"), keyClass, SQLDialect.class,
            Settings.class, Executor.class);
        try {
            return acquire.invoke(null, source, keyClass.getMethod("defaultSource").invoke(null), dialect,
                new Settings(), sameThread);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void release(Object pinned) throws Throwable {
        try {
            Accessible.method(pinned.getClass(), "release").invoke(pinned);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // --- recording fakes -------------------------------------------------------------------------

    private DataSource fakeDataSource() {
        Connection connection = fakeConnection(events);
        return (DataSource) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    events.add("getConnection");
                    return connection;
                }
                return objectMethodOrDefault(proxy, method, args, "fakeDataSource");
            });
    }

    private Connection fakeConnection(List<String> log) {
        // Starts autocommit=false by default: pools (Agroal, Hikari) can be configured to hand out
        // connections that way, and the lifecycle forces the mode rather than trusting the pool's
        // configuration. This default is the fixture in which removing the forcing fails.
        boolean[] autoCommit = {arrivesInAutocommit};
        boolean[] closed = {false};
        String database = product;
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "close" -> { closed[0] = true; log.add("close"); yield null; }
                case "abort" -> { closed[0] = true; log.add("abort"); yield null; }
                case "isClosed" -> closed[0];
                case "getMetaData" -> FakeSessionSettings.metaData(harness.classLoader(), database);
                case "setAutoCommit" -> { autoCommit[0] = (Boolean) args[0]; log.add("setAutoCommit:" + args[0]); yield null; }
                case "getAutoCommit" -> autoCommit[0];
                case "isValid" -> { log.add("isValid"); yield valid; }
                case "commit" -> { log.add("commit"); yield null; }
                case "rollback" -> {
                    if (rollbackThrows != null) {
                        throw rollbackThrows;
                    }
                    log.add("rollback");
                    yield null;
                }
                // The capture of session settings at acquire (executeQuery, logged), and the probe a
                // mutation field's settle runs before it commits (execute, not logged).
                case "createStatement" -> FakeSessionSettings.captureStatement(harness.classLoader(), captureRows,
                    () -> {
                        log.add("capture");
                        if (captureThrows != null) {
                            throw captureThrows;
                        }
                    });
                case "createArrayOf" -> {
                    Object elements = args[1];
                    yield Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{java.sql.Array.class},
                        (array, m, a) -> m.getName().equals("getArray")
                            ? elements : objectMethodOrDefault(array, m, a, "fakeArray"));
                }
                // The reset at release, the only prepared statement the runtime itself issues.
                case "prepareStatement" -> resetStatement(log, (String) args[0]);
                default -> objectMethodOrDefault(proxy, method, args, "fakeConnection");
            });
    }

    /** The database the fake connection's metadata names; PostgreSQL by default. */
    private String product = "PostgreSQL";
    /** The autocommit mode the fake connection arrives in; off by default, as a pool may lend it. */
    private boolean arrivesInAutocommit;
    /** When set, the fake connection's {@code rollback} throws it instead of logging. */
    private java.sql.SQLException rollbackThrows;
    /** What the fake connection's {@code isValid} answers; a live connection by default. */
    private boolean valid;
    /** Settings the fake capture answers with, as {@code {name, value}} rows; none by default. */
    private List<String[]> captureRows = List.of();
    /** When set, the fake capture throws it. */
    private RuntimeException captureThrows;
    /** When set, the fake reset's {@code execute} throws it. */
    private java.sql.SQLException resetThrows;
    /** The {@code {names, values}} the last reset bound, as {@code createArrayOf} received them. */
    private final List<Object[]> resetBound = new ArrayList<>();

    private java.sql.PreparedStatement resetStatement(List<String> log, String sql) {
        assertThat(FakeSessionSettings.isReset(sql)).as("the runtime prepares no statement but the reset").isTrue();
        Object[][] bound = new Object[2][];
        return (java.sql.PreparedStatement) Proxy.newProxyInstance(harness.classLoader(),
            new Class<?>[]{java.sql.PreparedStatement.class}, (statement, m, a) -> switch (m.getName()) {
                case "setArray" -> { bound[(Integer) a[0] - 1] = (Object[]) ((java.sql.Array) a[1]).getArray(); yield null; }
                case "execute" -> {
                    log.add("reset");
                    resetBound.clear();
                    resetBound.add(bound[0]);
                    resetBound.add(bound[1]);
                    if (resetThrows != null) {
                        throw resetThrows;
                    }
                    yield false;
                }
                default -> objectMethodOrDefault(statement, m, a, "fakeResetStatement");
            });
    }

    /** Handles Object methods on a proxy and returns type-appropriate defaults for everything else. */
    private static Object objectMethodOrDefault(Object proxy, Method method, Object[] args, String label) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> label;
            default -> defaultValue(method.getReturnType());
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
