package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.FunctionHooks;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.RawHook;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.Variable;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of the concrete {@code GraphitronSessionHook} that
 * {@link ConnectionRuntimeClassGenerator} emits from a configured {@code <sessionState>} (R429 slice 3).
 * The hook is emitted, never shipped, so the only honest assertion of its JDBC call shapes is to
 * compile the real emitted {@code TypeSpec} and drive it: {@link EmittedCodeHarness} compiles it, and
 * every assertion reads the call log recorded by a fake {@code Connection} whose
 * {@code prepareCall}/{@code prepareStatement} hand back recording {@code CallableStatement}/
 * {@code PreparedStatement} proxies, never the emitted source text.
 */
@UnitTier
class SessionHookImplGeneratorTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private EmittedCodeHarness harness;
    private final List<String> events = new ArrayList<>();

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void functionHook_withHandle_capturesOutHandleAndBindsItOnDisconnect() throws Throwable {
        Object hook = hookFor(SessionStateConfig.from(
            new RawHook("Pk_Ras.Connect", true), new RawHook("Pk_Ras.Disconnect", true), List.of()));

        Object handle = connect(hook, "claims-payload");
        assertThat(handle).isEqualTo("RAS-42");
        assertThat(events).containsExactly(
            "prepareCall:{ call Pk_Ras.Connect(?, ?) }",
            "setString:1:claims-payload",
            "registerOutParameter:2:VARCHAR",
            "execute",
            "getString:2",
            "close");

        events.clear();
        disconnect(hook, "RAS-42");
        assertThat(events).containsExactly(
            "prepareCall:{ call Pk_Ras.Disconnect(?) }",
            "setString:1:RAS-42",
            "execute",
            "close");
    }

    @Test
    void functionHook_noHandle_passesOnlyClaimsAndReturnsNull() throws Throwable {
        Object hook = hookFor(SessionStateConfig.from(
            new RawHook("Pk.Connect", false), new RawHook("Pk.Disconnect", false), List.of()));

        Object handle = connect(hook, "claims-payload");
        assertThat(handle).isNull();
        assertThat(events).containsExactly(
            "prepareCall:{ call Pk.Connect(?) }",
            "setString:1:claims-payload",
            "execute",
            "close");

        events.clear();
        disconnect(hook, null);
        assertThat(events).containsExactly(
            "prepareCall:{ call Pk.Disconnect() }",
            "execute",
            "close");
    }

    @Test
    void functionHook_unmountFree_disconnectIsNoOp() throws Throwable {
        Object hook = hookFor(SessionStateConfig.from(
            new RawHook("Pk.SetContext", false), new RawHook(null, false), List.of()));

        connect(hook, "claims-payload");
        assertThat(events).containsExactly(
            "prepareCall:{ call Pk.SetContext(?) }",
            "setString:1:claims-payload",
            "execute",
            "close");

        events.clear();
        disconnect(hook, null);
        // The explicit unmount-free opt-out issues no SQL at all.
        assertThat(events).isEmpty();
    }

    @Test
    void variablesSugar_connectSetsFromClaimsInOneRoundTrip_disconnectClearsSameVariables() throws Throwable {
        Object hook = hookFor(SessionStateConfig.from(null, null,
            List.of(new Variable("app.user_id", "sub"), new Variable("app.tenant", "tenant"))));

        Object handle = connect(hook, "claims-payload");
        assertThat(handle).as("the <variables> sugar carries no handle").isNull();
        assertThat(events).containsExactly(
            "prepareStatement:select set_config('app.user_id', c ->> 'sub', false), "
                + "set_config('app.tenant', c ->> 'tenant', false) from (select cast(? as jsonb) as c) claims",
            "setString:1:claims-payload",
            "execute",
            "close");

        events.clear();
        disconnect(hook, null);
        // Disconnect clears exactly the two variables connect set, to the empty string, no parameters.
        assertThat(events).containsExactly(
            "prepareStatement:select set_config('app.user_id', '', false), set_config('app.tenant', '', false)",
            "execute",
            "close");
    }

    // --- driving helpers -------------------------------------------------------------------------

    private Object hookFor(SessionStateConfig config) {
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, config)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compile(units);
        try {
            return harness.load(SCHEMA_PACKAGE + ".GraphitronSessionHook").getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Object connect(Object hook, String claims) throws Throwable {
        try {
            return hook.getClass().getMethod("connect", Connection.class, String.class)
                .invoke(hook, fakeConnection(), claims);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void disconnect(Object hook, String handle) throws Throwable {
        try {
            hook.getClass().getMethod("disconnect", Connection.class, String.class)
                .invoke(hook, fakeConnection(), handle);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // --- recording fakes -------------------------------------------------------------------------

    private Connection fakeConnection() {
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "prepareCall" -> {
                    events.add("prepareCall:" + args[0]);
                    yield fakeCallableStatement();
                }
                case "prepareStatement" -> {
                    events.add("prepareStatement:" + args[0]);
                    yield fakePreparedStatement();
                }
                default -> objectMethodOrDefault(proxy, method, args, "fakeConnection");
            });
    }

    private CallableStatement fakeCallableStatement() {
        return (CallableStatement) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{CallableStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                case "setString" -> { events.add("setString:" + args[0] + ":" + args[1]); yield null; }
                case "registerOutParameter" -> { events.add("registerOutParameter:" + args[0] + ":VARCHAR"); yield null; }
                case "execute" -> { events.add("execute"); yield false; }
                case "getString" -> { events.add("getString:" + args[0]); yield "RAS-42"; }
                case "close" -> { events.add("close"); yield null; }
                default -> objectMethodOrDefault(proxy, method, args, "fakeCallableStatement");
            });
    }

    private PreparedStatement fakePreparedStatement() {
        return (PreparedStatement) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                case "setString" -> { events.add("setString:" + args[0] + ":" + args[1]); yield null; }
                case "execute" -> { events.add("execute"); yield false; }
                case "close" -> { events.add("close"); yield null; }
                default -> objectMethodOrDefault(proxy, method, args, "fakePreparedStatement");
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
