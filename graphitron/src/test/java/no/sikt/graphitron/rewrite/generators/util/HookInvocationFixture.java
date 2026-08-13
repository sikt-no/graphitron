package no.sikt.graphitron.rewrite.generators.util;

import org.jooq.Configuration;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Consumer-shaped hook methods the emitted {@code GraphitronSessionHook} calls directly in
 * {@link SessionHookImplGeneratorTest}. Static recording state is shared with the harness's
 * child classloader (same class, parent-loaded), so the tests read invocations off
 * {@link #EVENTS} rather than off emitted source text.
 */
public final class HookInvocationFixture {

    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    public static Configuration lastConfiguration;
    public static Connection lastConnection;

    private HookInvocationFixture() {}

    public static void reset() {
        EVENTS.clear();
        lastConfiguration = null;
        lastConnection = null;
    }

    /** Configuration-seam mount with two payload parameters, returning a handle. */
    public static String mount(Configuration cfg, String tenant, Integer count) {
        lastConfiguration = cfg;
        EVENTS.add("mount:" + tenant + ":" + count);
        return "H:" + tenant;
    }

    /** Configuration-seam unmount bound to the handle. */
    public static void unmount(Configuration cfg, String handle) {
        lastConfiguration = cfg;
        EVENTS.add("unmount:" + handle);
    }

    /** Seam in the middle of the list: the seam rule is positional-convention-free. */
    public static String mountSeamInMiddle(String first, Configuration cfg, String second) {
        lastConfiguration = cfg;
        EVENTS.add("mountSeamInMiddle:" + first + ":" + second);
        return "H:" + first + second;
    }

    /** Raw-JDBC seam mount. */
    public static String mountConnection(Connection connection, String claims) {
        lastConnection = connection;
        EVENTS.add("mountConnection:" + claims);
        return "HC:" + claims;
    }

    /** Handle-ignoring unmount: only the seam, legal even when mount returns a handle. */
    public static void unmountHandleIgnoring(Connection connection) {
        lastConnection = connection;
        EVENTS.add("unmountHandleIgnoring");
    }

    /** Handle-less mount: void return, no payload. */
    public static void mountVoid(Configuration cfg) {
        lastConfiguration = cfg;
        EVENTS.add("mountVoid");
    }
}
