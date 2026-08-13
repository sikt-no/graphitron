package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.session.SessionHooks;
import no.sikt.graphitron.rewrite.session.SessionHooksFixtures;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.conf.Settings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of the emitted {@code GraphitronSessionHook}: one final class with static
 * {@code mount}/{@code unmount} methods calling the consumer's resolved methods directly. The
 * class is emitted, never shipped, so the honest assertion is to compile the real emitted
 * {@code TypeSpec} against a fixture consumer class ({@link HookInvocationFixture}) and drive it;
 * every assertion reads the fixture's recorded invocations, never emitted source text.
 *
 * <p>What this pins: the payload spreads into the consumer method in the mount's own declaration
 * order; the seam parameter lands wherever the consumer declared it (no positional convention);
 * a {@code Configuration}-seam method receives a provider-free {@code Configuration} carrying
 * the resolved source's dialect and settings; a {@code Connection}-seam method receives the
 * pinned connection itself; the unmount binds the handle exactly when its signature takes one;
 * and a mount-only configuration emits no {@code unmount} at all.
 */
@UnitTier
class SessionHookImplGeneratorTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";
    private static final String FIXTURE = HookInvocationFixture.class.getName();

    private EmittedCodeHarness harness;

    @BeforeEach
    void reset() {
        HookInvocationFixture.reset();
    }

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void mount_spreadsThePayloadInDeclarationOrder_andReturnsTheHandle() throws Throwable {
        var hooks = new SessionHooks.Handled(
            SessionHooksFixtures.hookRef(FIXTURE, "mount", ClassName.get(String.class),
                SessionHooksFixtures.configurationSeam(),
                SessionHooksFixtures.stringPayload("tenant"),
                SessionHooksFixtures.payload("count", ClassName.get(Integer.class))),
            ClassName.get(String.class),
            Optional.of(SessionHooksFixtures.hookRef(FIXTURE, "unmount", TypeName.VOID,
                SessionHooksFixtures.configurationSeam(),
                SessionHooksFixtures.handleParam(ClassName.get(String.class)))));
        Class<?> hookClass = compileHook(hooks);

        Object handle = hookClass.getMethod("mount",
                Connection.class, SQLDialect.class, Settings.class, String.class, Integer.class)
            .invoke(null, fakeConnection(), SQLDialect.POSTGRES, new Settings(), "t1", 7);

        assertThat(handle).isEqualTo("H:t1");
        assertThat(HookInvocationFixture.EVENTS).containsExactly("mount:t1:7");

        hookClass.getMethod("unmount", Connection.class, SQLDialect.class, Settings.class, String.class)
            .invoke(null, fakeConnection(), SQLDialect.POSTGRES, new Settings(), "H:t1");
        assertThat(HookInvocationFixture.EVENTS).containsExactly("mount:t1:7", "unmount:H:t1");
    }

    @Test
    void configurationSeam_receivesAProviderFreeConfigurationCarryingTheSourcesDialectAndSettings()
            throws Throwable {
        var hooks = new SessionHooks.Handled(
            SessionHooksFixtures.hookRef(FIXTURE, "mount", ClassName.get(String.class),
                SessionHooksFixtures.configurationSeam(),
                SessionHooksFixtures.stringPayload("tenant"),
                SessionHooksFixtures.payload("count", ClassName.get(Integer.class))),
            ClassName.get(String.class), Optional.empty());
        Class<?> hookClass = compileHook(hooks);

        var settings = new Settings().withRenderSchema(false);
        hookClass.getMethod("mount",
                Connection.class, SQLDialect.class, Settings.class, String.class, Integer.class)
            .invoke(null, fakeConnection(), SQLDialect.POSTGRES, settings, "t1", 7);

        var cfg = HookInvocationFixture.lastConfiguration;
        assertThat(cfg.dialect()).isEqualTo(SQLDialect.POSTGRES);
        assertThat(cfg.settings().isRenderSchema())
            .as("the resolved source's own Settings reach the consumer's mount")
            .isFalse();
        assertThat(cfg.transactionProvider().getClass().getName())
            .as("provider-free: the transaction-demarcation seam structurally cannot ride the hook's Configuration")
            .startsWith("org.jooq");
    }

    @Test
    void seamParameter_landsWhereTheConsumerDeclaredIt() throws Throwable {
        var hooks = new SessionHooks.Handled(
            SessionHooksFixtures.hookRef(FIXTURE, "mountSeamInMiddle", ClassName.get(String.class),
                SessionHooksFixtures.stringPayload("first"),
                SessionHooksFixtures.configurationSeam(),
                SessionHooksFixtures.stringPayload("second")),
            ClassName.get(String.class), Optional.empty());
        Class<?> hookClass = compileHook(hooks);

        Object handle = hookClass.getMethod("mount",
                Connection.class, SQLDialect.class, Settings.class, String.class, String.class)
            .invoke(null, fakeConnection(), SQLDialect.POSTGRES, new Settings(), "a", "b");

        assertThat(handle).isEqualTo("H:ab");
        assertThat(HookInvocationFixture.EVENTS).containsExactly("mountSeamInMiddle:a:b");
    }

    @Test
    void connectionSeam_receivesThePinnedConnectionItself_andHandleIgnoringUnmountTakesNoHandle()
            throws Throwable {
        var hooks = new SessionHooks.Handled(
            SessionHooksFixtures.hookRef(FIXTURE, "mountConnection", ClassName.get(String.class),
                SessionHooksFixtures.connectionSeam(),
                SessionHooksFixtures.stringPayload("claims")),
            ClassName.get(String.class),
            Optional.of(SessionHooksFixtures.hookRef(FIXTURE, "unmountHandleIgnoring", TypeName.VOID,
                SessionHooksFixtures.connectionSeam())));
        Class<?> hookClass = compileHook(hooks);

        Connection connection = fakeConnection();
        hookClass.getMethod("mount", Connection.class, SQLDialect.class, Settings.class, String.class)
            .invoke(null, connection, SQLDialect.POSTGRES, new Settings(), "c1");
        assertThat(HookInvocationFixture.lastConnection).isSameAs(connection);

        // A handle-ignoring unmount is legal beside a handle-returning mount: the emitted
        // unmount takes only the seam inputs, no handle parameter.
        var unmount = hookClass.getMethod("unmount", Connection.class, SQLDialect.class, Settings.class);
        unmount.invoke(null, connection, SQLDialect.POSTGRES, new Settings());
        assertThat(HookInvocationFixture.EVENTS).containsExactly("mountConnection:c1", "unmountHandleIgnoring");
    }

    @Test
    void mountOnly_emitsNoUnmountAtAll() throws Throwable {
        var hooks = new SessionHooks.HandleLess(
            SessionHooksFixtures.hookRef(FIXTURE, "mountVoid", TypeName.VOID,
                SessionHooksFixtures.configurationSeam()),
            Optional.empty());
        Class<?> hookClass = compileHook(hooks);

        assertThat(methodsNamed(hookClass, "unmount"))
            .as("mount-only: nothing is emitted for the absent <unmount>, stronger than a no-op")
            .isEmpty();

        var mount = hookClass.getMethod("mount", Connection.class, SQLDialect.class, Settings.class);
        assertThat(mount.getReturnType()).isEqualTo(void.class);
        mount.invoke(null, fakeConnection(), SQLDialect.POSTGRES, new Settings());
        assertThat(HookInvocationFixture.EVENTS).containsExactly("mountVoid");
    }

    // --- harness helpers ---------------------------------------------------------------------

    private Class<?> compileHook(SessionHooks hooks) {
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, false, hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compile(units);
        return harness.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.SESSION_HOOK_IMPL_CLASS_NAME);
    }

    private static List<Method> methodsNamed(Class<?> cls, String name) {
        return java.util.Arrays.stream(cls.getDeclaredMethods())
            .filter(m -> m.getName().equals(name))
            .toList();
    }

    private static Connection fakeConnection() {
        return (Connection) Proxy.newProxyInstance(
            SessionHookImplGeneratorTest.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "fakeConnection";
                default -> null;
            });
    }

}
