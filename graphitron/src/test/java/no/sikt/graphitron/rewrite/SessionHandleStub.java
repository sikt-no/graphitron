package no.sikt.graphitron.rewrite;

/**
 * Handle classes for the session-identity tests: what a {@code <sessionState><handle>} names and
 * what a {@code $session.<member>} binding reads. Read by the classpath census like any consumer
 * class, which is the point: the members come from the store, not from reflection.
 */
public final class SessionHandleStub {

    private SessionHandleStub() {}

    /** A Java record handle: its components are its members, read through the record accessors. */
    public record RecordHandle(long brukerId, Long sokerId) {}

    /** A base class whose getter the handle inherits: inherited members are excluded. */
    public static class HandleBase {
        public String getInherited() {
            return "inherited";
        }
    }

    /** A bean handle: a {@code get} getter, a boolean {@code is} getter, and an inherited getter. */
    public static class PojoHandle extends HandleBase {
        public String getTitle() {
            return "title";
        }

        public boolean isActive() {
            return true;
        }

        /** Not offered: a public field is not an accessor the store holds. */
        public String publicField;
    }

    /** A handle offering one property through two accessors, which has no single thing to read. */
    public static class AmbiguousHandle {
        public String getTitle() {
            return "title";
        }

        public boolean isTitle() {
            return true;
        }
    }
}
