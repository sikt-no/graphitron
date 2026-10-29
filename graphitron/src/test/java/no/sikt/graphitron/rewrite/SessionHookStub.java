package no.sikt.graphitron.rewrite;

import org.jooq.Configuration;

import java.io.IOException;
import java.sql.Connection;
import java.util.Optional;

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

    /** The tenant slot beside a payload, for an {@code Integer}-tenant build. */
    public static String mountTenantSlot(Configuration cfg, Optional<Integer> tenant, String claims) {
        throw new UnsupportedOperationException();
    }

    /** A bare {@code Integer} parameter: payload in every build, never taken for the key. */
    public static String mountBareInteger(Configuration cfg, Integer userId) {
        throw new UnsupportedOperationException();
    }

    // ===== Rejection shapes =====

    /** Two tenant slots for an {@code Integer}-tenant build. */
    public static void mountTwoTenantSlots(Configuration cfg, Optional<Integer> a, Optional<Integer> b) {
        throw new UnsupportedOperationException();
    }

    /** An {@code Optional<String>} against an {@code Integer} tenant column. */
    public static void mountOptionalString(Configuration cfg, Optional<String> tenant) {
        throw new UnsupportedOperationException();
    }

    /** An {@code Optional<Long>} against an {@code Integer} tenant column. */
    public static void mountOptionalLong(Configuration cfg, Optional<Long> tenant) {
        throw new UnsupportedOperationException();
    }

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

    // ===== Handle types whose members $session.<member> decodes =====

    /** A Java record handle: its components are its members, read through the record accessors. */
    public record RecordHandle(long brukerId, Long sokerId) {}

    /** A base class whose getter the handle inherits: inherited members are excluded. */
    public static class HandleBase {
        public String getInherited() { return "inherited"; }
    }

    /**
     * A non-record handle: a {@code get} getter, a boolean {@code is} getter and a public field are
     * members; a static getter, a parameterised getter, a private getter, a non-boolean {@code is}
     * and the inherited getter are not.
     */
    public static class PojoHandle extends HandleBase {
        public String label = "label";
        public static String staticName = "x";
        public String getPrincipal() { return "p"; }
        public boolean isActive() { return true; }
        public static String getStaticThing() { return "s"; }
        public String getWithArgument(String x) { return x; }
        String getPackagePrivate() { return "p"; }
        public String isNotBoolean() { return "n"; }
    }

    public static RecordHandle mountRecordHandle(Configuration cfg) {
        throw new UnsupportedOperationException();
    }

    public static PojoHandle mountPojoHandle(Configuration cfg) {
        throw new UnsupportedOperationException();
    }

    public static no.sikt.graphitron.rewrite.test.jooq.public_.udt.records.SessionHandleRecord mountJooqHandle(
            Configuration cfg) {
        throw new UnsupportedOperationException();
    }
}
