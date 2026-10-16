package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.TransactionContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier behavioural coverage of {@link GraphitronTransactionProviderGenerator}. The provider is
 * emitted (never a graphitron artifact), so the honest way to assert its begin/commit/rollback
 * semantics over the pinned connection is to compile the real {@link TypeSpec} and drive it:
 * {@link EmittedCodeHarness} compiles it once, and every assertion reads the call log recorded by a
 * fake {@code Connection}, never the emitted source text.
 *
 * <p>The instrumentation that <em>uses</em> this provider (pin → publish → release) is proven
 * end-to-end at the execution tier against real Postgres; this class
 * pins the transaction primitive in isolation: top-level autocommit demarcation, savepoint nesting,
 * and the {@code ROLLBACK_ONLY} commit policy that a subclass of jOOQ's {@code final} default provider
 * could not express.
 */
@UnitTier
class GraphitronTransactionProviderGeneratorTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> providerClass;
    private static Object commitPolicyCommit;
    private static Object commitPolicyRollbackOnly;

    private final List<String> events = new ArrayList<>();
    /** When set, the fake connection throws this from {@code releaseSavepoint}, after recording it. */
    private SQLException releaseFailure;
    /** When set, the fake connection throws this from {@code commit}, after recording it. */
    private SQLException commitFailure;

    @BeforeAll
    static void compileEmittedProvider() throws Exception {
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compile(units);
        providerClass = harness.load(SCHEMA_PACKAGE + ".GraphitronTransactionProvider");
        Class<?> policyClass = harness.load(SCHEMA_PACKAGE + ".GraphitronTransactionProvider$CommitPolicy");
        commitPolicyCommit = policyClass.getField("COMMIT").get(null);
        commitPolicyRollbackOnly = policyClass.getField("ROLLBACK_ONLY").get(null);
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
        releaseFailure = null;
        commitFailure = null;
    }

    @Test
    void topLevel_commitPolicy_commitsAndAssertsAutoCommit() throws Throwable {
        // No prior mode is captured: autocommit-on is the resting state graphitron asserts on a
        // connection it owns, so settle re-asserts it unconditionally after the commit.
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);
        commit(provider);

        assertThat(events).containsExactly("setAutoCommit:false", "commit", "setAutoCommit:true");
    }

    @Test
    void rollbackOnly_defersTheTransactionAndSavepointsTheField() throws Throwable {
        // ROLLBACK_ONLY is the rollback-everything dev mode: the operation transaction opens
        // once and stays open across the field's settle (no commit, no rollback, no autocommit
        // restore here), so the generated DML two-step's post-settle read-back observes the write.
        // PinnedConnection.release discards the whole transaction at operation completion.
        Object provider = newProvider(fakeConnection(), commitPolicyRollbackOnly);
        begin(provider);
        commit(provider);

        assertThat(events).containsExactly(
            "getAutoCommit", "setAutoCommit:false", "setSavepoint:sp1", "releaseSavepoint:sp1");
    }

    @Test
    void rollbackOnly_secondField_reusesTheOpenTransaction() throws Throwable {
        // A second top-level field must not re-open the deferred transaction: its writes join the
        // same open transaction (visible to later read-backs), scoped by its own savepoint.
        Object provider = newProvider(fakeConnection(), commitPolicyRollbackOnly);
        begin(provider);
        commit(provider);
        begin(provider);
        commit(provider);

        assertThat(events).containsExactly(
            "getAutoCommit", "setAutoCommit:false", "setSavepoint:sp1", "releaseSavepoint:sp1",
            "getAutoCommit", "setSavepoint:sp2", "releaseSavepoint:sp2");
    }

    @Test
    void rollbackOnly_failedField_rollsBackToItsSavepointOnly() throws Throwable {
        // Field independence survives in dev mode: a failed field discards exactly its own writes
        // and the operation transaction stays open for the fields after it.
        Object provider = newProvider(fakeConnection(), commitPolicyRollbackOnly);
        begin(provider);
        rollback(provider);

        assertThat(events).containsExactly(
            "getAutoCommit", "setAutoCommit:false", "setSavepoint:sp1", "rollbackToSavepoint:sp1");
    }

    @Test
    void provider_hasExactlyOneConstructor_andNoSettleCallbackSeam() {
        // The settle-completion Runnable died with the re-fire: the convenience constructor
        // existed only to pass an empty callback, so the provider collapses to one canonical
        // constructor, and no priorAutoCommit field survives (nothing reads the connection's
        // mode as a value to preserve).
        assertThat(providerClass.getConstructors()).hasSize(1);
        assertThat(providerClass.getConstructors()[0].getParameterTypes())
            .containsExactly(harnessConnectionParam(), commitPolicyCommit.getClass());
        assertThat(java.util.Arrays.stream(providerClass.getDeclaredFields())
            .map(java.lang.reflect.Field::getName))
            .doesNotContain("priorAutoCommit", "afterSettle");
    }

    private static Class<?> harnessConnectionParam() {
        return Connection.class;
    }

    @Test
    void topLevel_rollback_rollsBackAndAssertsAutoCommit() throws Throwable {
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);
        rollback(provider);

        assertThat(events).containsExactly("setAutoCommit:false", "rollback", "setAutoCommit:true");
    }

    @Test
    void nested_usesSavepoints_notASecondTopLevelTransaction() throws Throwable {
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);   // top-level: autocommit off
        begin(provider);   // nested: savepoint
        commit(provider);  // nested: release savepoint
        commit(provider);  // top-level: commit + restore

        assertThat(events).containsExactly(
            "setAutoCommit:false",
            "setSavepoint:sp1",
            "releaseSavepoint:sp1",
            "commit", "setAutoCommit:true");
    }

    @Test
    void nested_rollback_rollsBackToSavepointOnly() throws Throwable {
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);    // top-level
        begin(provider);    // nested savepoint
        rollback(provider); // nested: rollback to savepoint, not the whole tx
        commit(provider);   // top-level commit

        assertThat(events).containsExactly(
            "setAutoCommit:false",
            "setSavepoint:sp1",
            "rollbackToSavepoint:sp1",
            "commit", "setAutoCommit:true");
    }

    @Test
    void secondTopLevelTransaction_onTheSameProvider_assertsTheModeAgain() throws Throwable {
        // One provider per pinned connection serves the whole operation: consecutive top-level
        // transactions each turn autocommit off and settle back to the asserted resting state.
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);
        commit(provider);
        begin(provider);
        rollback(provider);

        assertThat(events).containsExactly(
            "setAutoCommit:false", "commit", "setAutoCommit:true",
            "setAutoCommit:false", "rollback", "setAutoCommit:true");
    }

    @Test
    void nested_commit_toleratesUnsupportedReleaseSavepoint() throws Throwable {
        // Oracle's driver does not implement releaseSavepoint. Releasing only frees the marker,
        // which dies with the enclosing transaction anyway, so the nested commit succeeds.
        releaseFailure = new SQLFeatureNotSupportedException("Unsupported feature");
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);
        begin(provider);
        commit(provider);
        commit(provider);

        assertThat(events).containsExactly(
            "setAutoCommit:false", "setSavepoint:sp1", "releaseSavepoint:sp1", "commit", "setAutoCommit:true");
    }

    @Test
    void nested_commit_toleratesSqlState0A() throws Throwable {
        // SQLState class 0A is "feature not supported", for drivers raising a plain SQLException.
        releaseFailure = new SQLException("Unsupported feature", "0A000");
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);
        begin(provider);
        commit(provider);
        commit(provider);

        assertThat(events).containsExactly(
            "setAutoCommit:false", "setSavepoint:sp1", "releaseSavepoint:sp1", "commit", "setAutoCommit:true");
    }

    @Test
    void rollbackOnly_fieldSettle_toleratesUnsupportedReleaseSavepoint() throws Throwable {
        // The dev-mode field settle is the second release site; it tolerates the same failure, and
        // the second field still joins the open transaction under its own savepoint.
        releaseFailure = new SQLFeatureNotSupportedException("Unsupported feature");
        Object provider = newProvider(fakeConnection(), commitPolicyRollbackOnly);
        begin(provider);
        commit(provider);
        begin(provider);
        commit(provider);

        assertThat(events).containsExactly(
            "getAutoCommit", "setAutoCommit:false", "setSavepoint:sp1", "releaseSavepoint:sp1",
            "getAutoCommit", "setSavepoint:sp2", "releaseSavepoint:sp2");
    }

    @Test
    void nested_releaseFailure_propagates_andTheFollowingRollbackClosesTheSameScope() throws Throwable {
        // Postgres refuses the release over an aborted transaction (25P02). That failure must
        // surface: swallowed, the enclosing COMMIT would silently roll back and report success.
        // jOOQ follows the failed commit with a rollback of the same scope, which must land on the
        // nested savepoint, not on the enclosing transaction.
        var aborted = new SQLException("current transaction is aborted", "25P02");
        releaseFailure = aborted;
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);
        begin(provider);
        assertThatThrownBy(() -> commit(provider))
            .isInstanceOf(DataAccessException.class)
            .hasCause(aborted);
        rollback(provider);

        assertThat(events).containsExactly(
            "setAutoCommit:false", "setSavepoint:sp1", "releaseSavepoint:sp1", "rollbackToSavepoint:sp1");

        // depth and the savepoint stack came back consistent: a further nested scope and the
        // top-level commit behave as on a fresh provider.
        releaseFailure = null;
        events.clear();
        begin(provider);
        commit(provider);
        commit(provider);

        assertThat(events).containsExactly(
            "setSavepoint:sp2", "releaseSavepoint:sp2", "commit", "setAutoCommit:true");
    }

    @Test
    void topLevel_commitFailure_isFollowedByASingleTopLevelRollback() throws Throwable {
        // A failing connection.commit() (a deferred constraint, say) leaves the scope open, so
        // jOOQ's follow-up rollback settles it as failed exactly once.
        var deferred = new SQLException("deferred constraint violated", "23503");
        commitFailure = deferred;
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);
        assertThatThrownBy(() -> commit(provider))
            .isInstanceOf(DataAccessException.class)
            .hasCause(deferred);
        rollback(provider);

        assertThat(events).containsExactly("setAutoCommit:false", "commit", "rollback", "setAutoCommit:true");

        commitFailure = null;
        events.clear();
        begin(provider);
        commit(provider);

        assertThat(events).containsExactly("setAutoCommit:false", "commit", "setAutoCommit:true");
    }

    // --- driving helpers -------------------------------------------------------------------------

    private Object newProvider(Connection connection, Object policy) throws Throwable {
        Class<?> policyClass = policy.getClass();
        return providerClass.getConstructor(Connection.class, policyClass).newInstance(connection, policy);
    }

    private void begin(Object provider) throws Throwable { invokeTx(provider, "begin"); }
    private void commit(Object provider) throws Throwable { invokeTx(provider, "commit"); }
    private void rollback(Object provider) throws Throwable { invokeTx(provider, "rollback"); }

    private void invokeTx(Object provider, String name) throws Throwable {
        try {
            providerClass.getMethod(name, TransactionContext.class).invoke(provider, (Object) null);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // --- recording fakes -------------------------------------------------------------------------

    private Connection fakeConnection() {
        // Each setSavepoint mints a distinct labelled savepoint, and release / rollback-to record
        // their target, so the log shows which scope each call closed.
        var savepointCount = new java.util.concurrent.atomic.AtomicInteger();
        // Stateful autocommit so the deferred-rollback arm's reuse of an already-open transaction
        // is observable (a second field's begin must not re-open it).
        var autoCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> { events.add("getAutoCommit"); yield autoCommit.get(); }
                case "setAutoCommit" -> {
                    events.add("setAutoCommit:" + args[0]);
                    autoCommit.set((Boolean) args[0]);
                    yield null;
                }
                case "commit" -> {
                    events.add("commit");
                    if (commitFailure != null) throw commitFailure;
                    yield null;
                }
                case "rollback" -> {
                    events.add(args != null && args.length == 1 ? "rollbackToSavepoint:" + args[0] : "rollback");
                    yield null;
                }
                case "setSavepoint" -> {
                    var label = "sp" + savepointCount.incrementAndGet();
                    events.add("setSavepoint:" + label);
                    yield savepoint(label);
                }
                case "releaseSavepoint" -> {
                    events.add("releaseSavepoint:" + args[0]);
                    if (releaseFailure != null) throw releaseFailure;
                    yield null;
                }
                default -> objectMethodOrDefault(proxy, method, args, "connection");
            });
    }

    private static Savepoint savepoint(String label) {
        return (Savepoint) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Savepoint.class},
            (p, m, a) -> objectMethodOrDefault(p, m, a, label));
    }

    private static Object objectMethodOrDefault(Object proxy, Method method, Object[] args, String label) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> label;
            default -> defaultValue(method.getReturnType());
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == char.class) return '\0';
        return 0;
    }
}
