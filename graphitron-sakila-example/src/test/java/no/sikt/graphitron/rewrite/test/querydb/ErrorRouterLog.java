package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.generated.schema.ErrorRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * What the generated {@link ErrorRouter} logs while a test runs: the records of its logger, read
 * for the duration of a {@code try}-with-resources block.
 *
 * <p>This module's slf4j binds to the JBoss LogManager that Quarkus brings, so the records are read
 * off that logger with a {@code java.util.logging} handler, as {@code NodeIdFoldWarningTest} reads
 * the encoder's; the binding formats the slf4j message before the record is published, and a
 * throwable passed to the log call arrives as {@link LogRecord#getThrown()}.
 *
 * <p>Other test classes run concurrently and fail fields of their own, so a test reads back only
 * the records {@link #mentioning} a text only it can name: the reference its own response quotes,
 * which every line the router logs for a failure carries.
 *
 * <p>The logger's level is the JVM's. A class that reads {@code DEBUG} lines
 * ({@link #captureWithDebug()}) raises it and puts back what it found, and is annotated
 * {@code @ResourceLock(ErrorRouterLog.LEVEL_LOCK)} so no two such classes are in flight at once.
 */
final class ErrorRouterLog implements AutoCloseable {

    /** Annotate a class that calls {@link #captureWithDebug()} {@code @ResourceLock(ErrorRouterLog.LEVEL_LOCK)}. */
    static final String LEVEL_LOCK = "error-router-logger-level";

    private static final int ERROR = org.jboss.logmanager.Level.ERROR.intValue();
    private static final int DEBUG = org.jboss.logmanager.Level.DEBUG.intValue();

    private final Logger logger = org.jboss.logmanager.Logger.getLogger(ErrorRouter.class.getName());
    private final List<LogRecord> recorded = Collections.synchronizedList(new ArrayList<>());
    private final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) { recorded.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    private final boolean levelRaised;
    private final Level inherited;

    private ErrorRouterLog(boolean debug) {
        levelRaised = debug;
        inherited = logger.getLevel();
        if (debug) {
            logger.setLevel(org.jboss.logmanager.Level.DEBUG);
        }
        logger.addHandler(handler);
    }

    /** Reads the router's records at the level the logger already has. */
    static ErrorRouterLog capture() {
        return new ErrorRouterLog(false);
    }

    /** Reads the router's records with its logger at {@code DEBUG}; the class holds {@link #LEVEL_LOCK}. */
    static ErrorRouterLog captureWithDebug() {
        return new ErrorRouterLog(true);
    }

    /** The {@code ERROR} records whose message contains {@code text}. */
    List<LogRecord> errorsMentioning(String text) {
        return mentioning(text, ERROR);
    }

    /** The {@code DEBUG} records whose message contains {@code text}. */
    List<LogRecord> debugsMentioning(String text) {
        return mentioning(text, DEBUG);
    }

    /** Every record, at any level, whose message contains {@code text}. */
    List<LogRecord> mentioning(String text) {
        return mentioning(text, null);
    }

    private List<LogRecord> mentioning(String text, Integer level) {
        synchronized (recorded) {
            return recorded.stream()
                .filter(record -> level == null || record.getLevel().intValue() == level)
                .filter(record -> record.getMessage() != null && record.getMessage().contains(text))
                .toList();
        }
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        if (levelRaised) {
            logger.setLevel(inherited);
        }
    }
}
