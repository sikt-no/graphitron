package no.sikt.graphitron.rewrite.test.services;

import org.jooq.DSLContext;

/**
 * Services binding one member of a {@link SessionMemberHandle} under {@code $session.<member>}: a
 * boxed parameter taking the primitive member and a primitive one taking it, both accepted, and a
 * primitive parameter taking the boxed member, refused as {@code SESSION_BINDING_TYPE_MISMATCH},
 * since a null member has no primitive. Each answers the Integer an {@code Int} field is handed, so
 * only the binding is judged. Never called.
 */
public final class SessionMemberService {

    private SessionMemberService() {}

    public static Integer brukerOf(DSLContext dsl, Long brukerId) {
        return null;
    }

    public static Integer brukerOfPrimitive(DSLContext dsl, long brukerId) {
        return null;
    }

    public static Integer sokerOfPrimitive(DSLContext dsl, long sokerId) {
        return null;
    }
}
