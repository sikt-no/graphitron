package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Builds a real generated {@code ConnectionSource} for the emitted-code harness tests, through the
 * generated {@code ConnectionSources.mounting(pool, mount, unmount)}: the pool maps a key's tenant
 * (or {@code "default"} for a key without one) to the test's fake {@code DataSource}, the mount runs
 * the recognisable statement the tests' recording fake connections observe on the connection it is
 * given, the way a consumer's lambda would, and the unmount runs the clearing statement. The
 * generated {@code Mount} and {@code Unmount} interfaces are generated types, so the callbacks are
 * {@link Proxy}s over them; nothing here reads emitted source text.
 */
final class RecordingSource {

    private RecordingSource() {}

    /** The pool label a key without a tenant maps to. */
    static final String DEFAULT = "default";

    private static final Map<ClassLoader, List<Optional<?>>> MOUNTED_BY_LOADER =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The tenant each mount received on {@code harness}'s generated classes, in call order. Kept per
     * harness because test classes run concurrently and each compiles its own generated world, so
     * one class's mounts are never another's; tests clear it before each case.
     */
    static List<Optional<?>> mountedTenants(EmittedCodeHarness harness) {
        return MOUNTED_BY_LOADER.computeIfAbsent(harness.classLoader(), l -> new CopyOnWriteArrayList<>());
    }

    /**
     * A mounting source over {@code pools}, keyed by tenant (a key without one is {@link #DEFAULT}),
     * whose mount issues {@code set_config(...)} and returns {@code "handle:" + claims}, and whose
     * unmount issues the clearing statement the fakes label as the disconnect phase. A tenant
     * {@code pools} does not name is refused with the generated {@code KeyNotPermitted}.
     */
    static Object create(EmittedCodeHarness harness, String schemaPackage, String claims,
                         Map<String, DataSource> pools) throws Exception {
        return create(harness, schemaPackage, claims, poolOf(harness, schemaPackage, pools));
    }

    /** As {@link #create(EmittedCodeHarness, String, String, Map)}, every key on {@code pool}. */
    static Object create(EmittedCodeHarness harness, String schemaPackage, String claims, DataSource pool)
            throws Exception {
        return create(harness, schemaPackage, claims, key -> pool);
    }

    /** As {@link #create(EmittedCodeHarness, String, String, Map)}, over an arbitrary pool function. */
    static Object create(EmittedCodeHarness harness, String schemaPackage, String claims,
                         Function<Object, DataSource> pool) throws Exception {
        return mounting(harness, schemaPackage, pool,
            (connection, key) -> {
                recordTenant(key);
                connection.prepareStatement("select set_config('app.uid', ?, false)").execute();
                return "handle:" + claims;
            },
            (connection, key, handle) -> connection.prepareStatement("select set_config('app.uid', '', false)").execute());
    }

    /**
     * A mounting source over {@code pool} and {@link LifecycleHookFixture}: each mount returns a
     * fresh handle and logs {@code connect->Hn} into the fixture's sink, each unmount logs
     * {@code disconnect:Hn}, in the same interleaved log the lifecycle test's fake connections write.
     */
    static Object createLifecycle(EmittedCodeHarness harness, String schemaPackage, DataSource pool) throws Exception {
        return mounting(harness, schemaPackage, key -> pool,
            (connection, key) -> LifecycleHookFixture.mount(connection, "claims-payload"),
            (connection, key, handle) -> LifecycleHookFixture.unmount(connection, (String) handle));
    }

    /** {@code ConnectionSources.mounting(pool, mount, unmount)} over plain Java callbacks. */
    static Object mounting(EmittedCodeHarness harness, String schemaPackage, Function<Object, DataSource> pool,
                           MountBody mount, UnmountBody unmount) throws Exception {
        Class<?> sources = harness.load(schemaPackage + ".ConnectionSources");
        Class<?> mountInterface = harness.load(schemaPackage + ".ConnectionSources$Mount");
        Class<?> unmountInterface = harness.load(schemaPackage + ".ConnectionSources$Unmount");
        Object mountProxy = mount == null ? null : Proxy.newProxyInstance(harness.classLoader(),
            new Class<?>[]{mountInterface},
            (proxy, method, args) -> method.getName().equals("mount")
                ? mount.mount((Connection) args[0], args[1])
                : objectMethod(proxy, method, args));
        Object unmountProxy = unmount == null ? null : Proxy.newProxyInstance(harness.classLoader(),
            new Class<?>[]{unmountInterface},
            (proxy, method, args) -> {
                if (!method.getName().equals("unmount")) {
                    return objectMethod(proxy, method, args);
                }
                unmount.unmount((Connection) args[0], args[1], args.length > 2 ? args[2] : null);
                return null;
            });
        try {
            return sources.getMethod("mounting", Function.class, mountInterface, unmountInterface)
                .invoke(null, pool, mountProxy, unmountProxy);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /**
     * {@code ConnectionSources.of(pool)} over {@code pools}, keyed as
     * {@link #create(EmittedCodeHarness, String, String, Map)} keys them.
     */
    static Object plain(EmittedCodeHarness harness, String schemaPackage, Map<String, DataSource> pools)
            throws Exception {
        try {
            return harness.load(schemaPackage + ".ConnectionSources").getMethod("of", Function.class)
                .invoke(null, poolOf(harness, schemaPackage, pools));
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** The tenant {@code key} carries, or {@link #DEFAULT}. */
    static String labelOf(Object key) {
        Optional<?> tenant = tenantOf(key);
        return tenant.map(String::valueOf).orElse(DEFAULT);
    }

    private static Function<Object, DataSource> poolOf(EmittedCodeHarness harness, String schemaPackage,
                                                       Map<String, DataSource> pools) {
        return key -> {
            DataSource pool = pools.get(labelOf(key));
            if (pool == null) {
                throw keyNotPermitted(harness, schemaPackage, "Tenant '" + labelOf(key) + "' is not hosted here.");
            }
            return pool;
        };
    }

    private static RuntimeException keyNotPermitted(EmittedCodeHarness harness, String schemaPackage, String message) {
        try {
            return (RuntimeException) harness.load(schemaPackage + ".KeyNotPermitted")
                .getConstructor(String.class).newInstance(message);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Optional<?> tenantOf(Object key) {
        try {
            return (Optional<?>) key.getClass().getMethod("tenant").invoke(key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void recordTenant(Object key) {
        MOUNTED_BY_LOADER.computeIfAbsent(key.getClass().getClassLoader(), l -> new CopyOnWriteArrayList<>())
            .add(tenantOf(key));
    }

    private static Object objectMethod(Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "RecordingSource";
            default -> null;
        };
    }

    /** A mount body: sets state on {@code connection} for {@code key}, returns the handle (ignored without one). */
    @FunctionalInterface
    interface MountBody {
        Object mount(Connection connection, Object key) throws Exception;
    }

    /** An unmount body: clears what the mount set; {@code handle} is {@code null} in a build without one. */
    @FunctionalInterface
    interface UnmountBody {
        void unmount(Connection connection, Object key, Object handle) throws Exception;
    }
}
