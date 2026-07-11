package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.TransactionContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier behavioural coverage of {@link GraphitronTransactionProviderGenerator}. The provider is
 * emitted (never a graphitron artifact), so the honest way to assert its begin/commit/rollback
 * semantics over the pinned connection is to compile the real {@link TypeSpec} and drive it:
 * {@link EmittedCodeHarness} compiles it once, and every assertion reads the call log recorded by a
 * fake {@code Connection}, never the emitted source text.
 *
 * <p>The instrumentation that <em>uses</em> this provider (pin → publish → release) is proven
 * end-to-end at the execution tier against real Postgres (per the R429 slice-2 test plan); this class
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
    }

    @Test
    void topLevel_commitPolicy_commitsAndRestoresAutoCommit() throws Throwable {
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);
        commit(provider);

        assertThat(events).containsExactly("getAutoCommit", "setAutoCommit:false", "commit", "setAutoCommit:true");
    }

    @Test
    void rollbackOnly_defersTheTransactionAndSavepointsTheField() throws Throwable {
        // ROLLBACK_ONLY is R428's rollback-everything dev mode: the operation transaction opens
        // once and stays open across the field's settle (no commit, no rollback, no autocommit
        // restore here), so the generated DML two-step's post-settle read-back observes the write.
        // PinnedConnection.release discards the whole transaction at operation completion.
        Object provider = newProvider(fakeConnection(), commitPolicyRollbackOnly);
        begin(provider);
        commit(provider);

        assertThat(events).containsExactly(
            "getAutoCommit", "setAutoCommit:false", "setSavepoint", "releaseSavepoint");
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
            "getAutoCommit", "setAutoCommit:false", "setSavepoint", "releaseSavepoint",
            "getAutoCommit", "setSavepoint", "releaseSavepoint");
    }

    @Test
    void rollbackOnly_failedField_rollsBackToItsSavepointOnly() throws Throwable {
        // Field independence survives in dev mode: a failed field discards exactly its own writes
        // and the operation transaction stays open for the fields after it.
        Object provider = newProvider(fakeConnection(), commitPolicyRollbackOnly);
        begin(provider);
        rollback(provider);

        assertThat(events).containsExactly(
            "getAutoCommit", "setAutoCommit:false", "setSavepoint", "rollbackToSavepoint");
    }

    @Test
    void rollbackOnly_neverFiresTheSettleCallback() throws Throwable {
        // Nothing settles until release under the deferred realization, so the session-identity
        // re-fire seam stays unfired; mounted identity is session-scoped state the eventual
        // release rollback cannot revert.
        Object provider = newProvider(fakeConnection(), commitPolicyRollbackOnly, () -> events.add("afterSettle"));
        begin(provider);
        commit(provider);
        begin(provider);
        rollback(provider);

        assertThat(events).doesNotContain("afterSettle");
    }

    @Test
    void topLevel_rollback_rollsBackAndRestoresAutoCommit() throws Throwable {
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);
        rollback(provider);

        assertThat(events).containsExactly("getAutoCommit", "setAutoCommit:false", "rollback", "setAutoCommit:true");
    }

    @Test
    void nested_usesSavepoints_notASecondTopLevelTransaction() throws Throwable {
        Object provider = newProvider(fakeConnection(), commitPolicyCommit);
        begin(provider);   // top-level: autocommit off
        begin(provider);   // nested: savepoint
        commit(provider);  // nested: release savepoint
        commit(provider);  // top-level: commit + restore

        assertThat(events).containsExactly(
            "getAutoCommit", "setAutoCommit:false",
            "setSavepoint",
            "releaseSavepoint",
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
            "getAutoCommit", "setAutoCommit:false",
            "setSavepoint",
            "rollbackToSavepoint",
            "commit", "setAutoCommit:true");
    }

    @Test
    void settleCompletionCallback_runsAfterEveryTopLevelSettle_neverOnSavepoints() throws Throwable {
        // The callback is opaque to the provider (commit policy stays its one axis); the acquisition
        // machinery wires the session-identity re-fire through it. It must run after autocommit is
        // restored (so the re-fire executes outside the settled transaction), on both settle outcomes,
        // and never for nested savepoint settles.
        Object provider = newProvider(fakeConnection(), commitPolicyCommit, () -> events.add("afterSettle"));
        begin(provider);    // top-level
        begin(provider);    // nested savepoint
        commit(provider);   // nested: no settle, no callback
        commit(provider);   // top-level settle: callback fires after autocommit restore
        begin(provider);    // second top-level transaction on the same provider
        rollback(provider); // rollback settles too

        assertThat(events).containsExactly(
            "getAutoCommit", "setAutoCommit:false",
            "setSavepoint",
            "releaseSavepoint",
            "commit", "setAutoCommit:true", "afterSettle",
            "getAutoCommit", "setAutoCommit:false",
            "rollback", "setAutoCommit:true", "afterSettle");
    }

    // --- driving helpers -------------------------------------------------------------------------

    private Object newProvider(Connection connection, Object policy) throws Throwable {
        Class<?> policyClass = policy.getClass();
        return providerClass.getConstructor(Connection.class, policyClass).newInstance(connection, policy);
    }

    private Object newProvider(Connection connection, Object policy, Runnable afterSettle) throws Throwable {
        Class<?> policyClass = policy.getClass();
        return providerClass.getConstructor(Connection.class, policyClass, Runnable.class)
            .newInstance(connection, policy, afterSettle);
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
        Object savepoint = Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{java.sql.Savepoint.class},
            (p, m, a) -> objectMethodOrDefault(p, m, a, "savepoint"));
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
                case "commit" -> { events.add("commit"); yield null; }
                case "rollback" -> {
                    events.add(args != null && args.length == 1 ? "rollbackToSavepoint" : "rollback");
                    yield null;
                }
                case "setSavepoint" -> { events.add("setSavepoint"); yield savepoint; }
                case "releaseSavepoint" -> { events.add("releaseSavepoint"); yield null; }
                default -> objectMethodOrDefault(proxy, method, args, "connection");
            });
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
