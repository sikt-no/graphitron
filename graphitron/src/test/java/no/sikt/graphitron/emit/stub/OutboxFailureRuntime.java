package no.sikt.graphitron.emit.stub;

import graphql.schema.DataFetchingEnvironment;
import org.jooq.DSLContext;

import java.util.List;

/**
 * The generated runtime a failure companion names, in one place: the connection carrier its fetcher
 * registers it with, the outbox exception it throws, and the reads of a claimed error, its type and
 * one field of it. A test hands the carrier's registration back to itself and runs it, as settle
 * does after a rollback.
 */
public final class OutboxFailureRuntime {

    private OutboxFailureRuntime() {}

    /** What the generated carrier holds for a field: its failure messages, as the fetcher registered them. */
    @FunctionalInterface
    public interface FieldRollback {
        void write(DSLContext dsl, Throwable claimed);
    }

    /** The last registration, with the events it named. */
    public static FieldRollback registered;
    public static List<String> registeredEvents;

    /** The carrier's registration: kept for the test to run. */
    public static void onFieldRollback(DataFetchingEnvironment env, List<String> events, FieldRollback write) {
        registered = write;
        registeredEvents = events;
    }

    /** The generated outbox exception. */
    public static final class EmitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EmitException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * The type choice the errors union's resolver makes: here by the claimed exception's message,
     * which names the member it stands for.
     */
    public static String typeOf(String container, Object source) {
        return source instanceof Throwable t ? t.getMessage() : null;
    }

    /** One field of a member type read off the claimed error: the member and the field, named. */
    public static Object read(String member, String field, Object source) {
        return member + "." + field;
    }
}
