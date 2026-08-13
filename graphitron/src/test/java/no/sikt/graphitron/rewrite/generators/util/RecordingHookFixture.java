package no.sikt.graphitron.rewrite.generators.util;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Consumer-shaped session-hook methods for the emitted-code harness tests: a raw-JDBC
 * ({@code Connection}-seam) mount/unmount pair whose statements the tests' recording fake
 * connections observe. Declares no checked exceptions, per the hook contract; the JDBC residue
 * wraps unchecked, which is also what exercises the fail-closed eviction when a fake statement
 * throws.
 */
public final class RecordingHookFixture {

    private RecordingHookFixture() {}

    /** Mounts by issuing a recognizable statement; returns a handle derived from the payload. */
    public static String mount(Connection connection, String claims) {
        try {
            connection.prepareStatement("select set_config('app.uid', ?, false)").execute();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return "handle:" + claims;
    }

    /** Unmounts by issuing the clearing statement the fakes label as the disconnect phase. */
    public static void unmount(Connection connection, String handle) {
        try {
            connection.prepareStatement("select set_config('app.uid', '', false)").execute();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
