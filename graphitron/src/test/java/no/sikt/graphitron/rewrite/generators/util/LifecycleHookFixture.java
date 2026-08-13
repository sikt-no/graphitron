package no.sikt.graphitron.rewrite.generators.util;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumer-shaped hook pair for {@link ConnectionRuntimeClassGeneratorTest}: mounts return a
 * fresh handle per call ({@code H1}, {@code H2}, ...), logged as {@code connect->Hn}, and
 * unmounts log the handle they were bound to, into the same sink the tests' fake connections
 * write, so lifecycle ordering is asserted over one interleaved log. Static state is shared with
 * the harness's child classloader (same class, parent-loaded). Failures are unchecked, per the
 * hook contract (checked exceptions are a build-time rejection).
 */
public final class LifecycleHookFixture {

    public static List<String> sink = new ArrayList<>();
    public static RuntimeException mountThrows;
    public static RuntimeException unmountThrows;
    private static int mounts;

    private LifecycleHookFixture() {}

    /** Points the fixture at the running test's event list and clears failure configuration. */
    public static void reset(List<String> events) {
        sink = events;
        mountThrows = null;
        unmountThrows = null;
        mounts = 0;
    }

    public static String mount(Connection connection, String claims) {
        mounts++;
        String handle = "H" + mounts;
        sink.add("connect->" + handle);
        if (mountThrows != null) {
            throw mountThrows;
        }
        return handle;
    }

    public static void unmount(Connection connection, String handle) {
        sink.add("disconnect:" + handle);
        if (unmountThrows != null) {
            throw unmountThrows;
        }
    }
}
