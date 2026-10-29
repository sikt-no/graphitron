package no.sikt.graphitron.rewrite;

import org.jooq.Condition;

/**
 * The {@code @service} and {@code @condition} methods the session-binding rejection tests name.
 * Public, unlike the package-private stubs beside it, because the store reads a code reference's
 * class from the census, which holds public classes only.
 */
public final class SessionBindingStub {

    private SessionBindingStub() {}

    /** A service parameter bound to the {@code brukerId} member of the record handle. */
    public static Long brukerOf(org.jooq.DSLContext dsl, Long brukerId) {
        throw new UnsupportedOperationException();
    }

    /** The primitive form of {@link #brukerOf}. */
    public static Long brukerOfPrimitive(org.jooq.DSLContext dsl, long brukerId) {
        throw new UnsupportedOperationException();
    }

    /** A boxed member bound to a primitive parameter. */
    public static Long sokerOfPrimitive(org.jooq.DSLContext dsl, long sokerId) {
        throw new UnsupportedOperationException();
    }

    /** A boxed parameter bound to the primitive {@code long} member {@code brukerId}. */
    public static Condition sessionBoxed(org.jooq.Table<?> table, Long brukerId) {
        throw new UnsupportedOperationException();
    }

    /** A parameter whose type is neither the member's nor its box. */
    public static Condition sessionWrongType(org.jooq.Table<?> table, String brukerId) {
        throw new UnsupportedOperationException();
    }

    /** The whole handle. */
    public static Condition sessionWhole(org.jooq.Table<?> table, SessionHandleStub.RecordHandle handle) {
        throw new UnsupportedOperationException();
    }
}
