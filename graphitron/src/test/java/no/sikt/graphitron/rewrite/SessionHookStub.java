package no.sikt.graphitron.rewrite;

import org.jooq.Configuration;

import java.io.IOException;
import java.sql.Connection;

/**
 * Hook-shaped methods for {@code SessionHookResolutionTest}: every signature the
 * {@code <sessionState>} resolver accepts or rejects, reflected by name through
 * {@code ServiceCatalog#resolveSessionHooks}. Compiled with {@code -parameters} (the test
 * tree's default), so payload parameter names are real; the name-less complement lives in
 * {@code no.sikt.graphitron.codereferences.noparams.NoParamsServiceStub}.
 */
public final class SessionHookStub {

    private SessionHookStub() {}

    // ===== Acceptance shapes =====

    /** The headline shape: {@code Configuration} seam, one String payload, a returned handle. */
    public static String mountHandled(Configuration cfg, String claims) {
        throw new UnsupportedOperationException();
    }

    /** The paired unmount: seam plus one handle parameter matching the mount's return type. */
    public static void unmountHandled(Configuration cfg, String handle) {
        throw new UnsupportedOperationException();
    }

    /** Raw-JDBC seam, no payload, no handle: the minimal {@code HandleLess} mount. */
    public static void mountHandleLess(Connection connection) {
        throw new UnsupportedOperationException();
    }

    /** Handle-ignoring unmount: seam only, valid against any mount. */
    public static void unmountSeamOnly(Connection connection) {
        throw new UnsupportedOperationException();
    }

    /** An unmount whose return value the runtime discards. */
    public static int unmountReturning(Configuration cfg, String handle) {
        throw new UnsupportedOperationException();
    }

    /** Several payload parameters, in a declaration order the resolver must preserve. */
    public static String mountMultiPayload(Configuration cfg, String claims, Long fnr) {
        throw new UnsupportedOperationException();
    }

    /**
     * The jOOQ-generated triple-overload shape: three same-named declarations of which exactly
     * one carries a seam parameter, so the seam filter resolves to the executing method.
     */
    public static String overloaded(Configuration cfg, String claims) {
        throw new UnsupportedOperationException();
    }

    /** Field-expression overload: no seam, never picked. */
    public static Object overloaded(String claims) {
        throw new UnsupportedOperationException();
    }

    /** Field-arguments overload: no seam, never picked. */
    public static Object overloaded(Object claims, Object more) {
        throw new UnsupportedOperationException();
    }

    // ===== Rejection shapes =====

    /** No seam parameter at all. */
    public static void noSeam(String claims) {
        throw new UnsupportedOperationException();
    }

    /** Two seam parameters: not a qualifying candidate either. */
    public static void twoSeams(Configuration cfg, Connection connection) {
        throw new UnsupportedOperationException();
    }

    /** Two same-named seam-bearing declarations: genuinely ambiguous under the seam filter. */
    public static void ambiguous(Configuration cfg) {
        throw new UnsupportedOperationException();
    }

    /** The other qualifying {@code ambiguous} candidate. */
    public static void ambiguous(Connection connection) {
        throw new UnsupportedOperationException();
    }

    /** Instance method: hooks are called without an instance, so this is rejected. */
    public void notStatic(Configuration cfg) {
        throw new UnsupportedOperationException();
    }

    /** Declares a checked exception the hook contract forbids. */
    public static void throwsChecked(Configuration cfg) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** A mount whose handle type disagrees with {@link #unmountHandled}'s parameter. */
    public static Integer mountIntHandle(Configuration cfg) {
        throw new UnsupportedOperationException();
    }
}
