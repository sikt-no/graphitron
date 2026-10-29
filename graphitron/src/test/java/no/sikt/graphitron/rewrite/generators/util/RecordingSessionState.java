package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import org.jooq.Configuration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Builds a real generated {@code SessionState} for the emitted-code harness tests: its mount and
 * unmount callbacks run the recognisable statements the tests' recording fake connections observe,
 * the way a consumer's lambdas would, through the {@code MountedSession}'s provider-free
 * {@code Configuration}. The generated {@code Mount} and {@code Unmount} interfaces are generated
 * types, so the callbacks are {@link Proxy}s over them; nothing here reads emitted source text.
 */
final class RecordingSessionState {

    private RecordingSessionState() {}

    private static final java.util.Map<ClassLoader, List<Optional<?>>> MOUNTED_BY_LOADER =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The tenant each mount received on {@code harness}'s generated classes, in call order, in a
     * multi-tenant build. Kept per harness because test classes run concurrently and each compiles
     * its own generated world, so one class's mounts are never another's; tests clear it before
     * each case.
     */
    static List<Optional<?>> mountedTenants(EmittedCodeHarness harness) {
        return MOUNTED_BY_LOADER.computeIfAbsent(harness.classLoader(), l -> new CopyOnWriteArrayList<>());
    }

    /**
     * A {@code SessionState.of(mount, unmount)} whose mount issues {@code set_config(...)} and
     * returns {@code "handle:" + claims}, and whose unmount issues the clearing statement the fakes
     * label as the disconnect phase.
     */
    static Object create(EmittedCodeHarness harness, String schemaPackage, String claims) throws Exception {
        Class<?> stateClass = harness.load(schemaPackage + ".SessionState");
        Class<?> mountInterface = harness.load(schemaPackage + ".SessionState$Mount");
        Class<?> unmountInterface = harness.load(schemaPackage + ".SessionState$Unmount");
        Object mount = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{mountInterface},
            (proxy, method, args) -> {
                if (!method.getName().equals("mount")) {
                    return objectMethod(proxy, method, args);
                }
                Object session = args[0];
                recordTenant(session);
                run(session, "select set_config('app.uid', ?, false)");
                return "handle:" + claims;
            });
        Object unmount = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{unmountInterface},
            (proxy, method, args) -> {
                if (!method.getName().equals("unmount")) {
                    return objectMethod(proxy, method, args);
                }
                run(args[0], "select set_config('app.uid', '', false)");
                return null;
            });
        try {
            return stateClass.getMethod("of", mountInterface, unmountInterface).invoke(null, mount, unmount);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /**
     * A {@code SessionState.of(mount, unmount)} over {@link LifecycleHookFixture}: each mount
     * returns a fresh handle and logs {@code connect->Hn} into the fixture's sink, each unmount logs
     * {@code disconnect:Hn}, in the same interleaved log the lifecycle test's fake connections write.
     */
    static Object createLifecycle(EmittedCodeHarness harness, String schemaPackage) throws Exception {
        Class<?> stateClass = harness.load(schemaPackage + ".SessionState");
        Class<?> mountInterface = harness.load(schemaPackage + ".SessionState$Mount");
        Class<?> unmountInterface = harness.load(schemaPackage + ".SessionState$Unmount");
        Object mount = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{mountInterface},
            (proxy, method, args) -> method.getName().equals("mount")
                ? LifecycleHookFixture.mount(connectionOf(args[0]), "claims-payload")
                : objectMethod(proxy, method, args));
        Object unmount = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{unmountInterface},
            (proxy, method, args) -> {
                if (method.getName().equals("unmount")) {
                    LifecycleHookFixture.unmount(connectionOf(args[0]), (String) args[1]);
                    return null;
                }
                return objectMethod(proxy, method, args);
            });
        try {
            return stateClass.getMethod("of", mountInterface, unmountInterface).invoke(null, mount, unmount);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static Connection connectionOf(Object session) throws ReflectiveOperationException, SQLException {
        var configuration = (Configuration) session.getClass().getMethod("configuration").invoke(session);
        return configuration.connectionProvider().acquire();
    }

    private static void recordTenant(Object session) throws ReflectiveOperationException {
        try {
            MOUNTED_BY_LOADER.computeIfAbsent(session.getClass().getClassLoader(),
                    l -> new CopyOnWriteArrayList<>())
                .add((Optional<?>) session.getClass().getMethod("tenant").invoke(session));
        } catch (NoSuchMethodException singleTenant) {
            // A single-tenant MountedSession declares no tenant(), by construction.
        }
    }

    private static void run(Object session, String sql) throws ReflectiveOperationException, SQLException {
        var configuration = (Configuration) session.getClass().getMethod("configuration").invoke(session);
        Connection connection = configuration.connectionProvider().acquire();
        connection.prepareStatement(sql).execute();
    }

    private static Object objectMethod(Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "RecordingSessionState";
            default -> null;
        };
    }
}
