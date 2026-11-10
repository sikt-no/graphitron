package no.sikt.graphitron.rewrite.test.internal;

/**
 * The resource key every test class that sets the level of the generated
 * {@code NodeIdEncoder}'s logger locks, so that no two of them are in flight at once.
 *
 * <p>A test that reads the encoder's {@code DEBUG} lines raises that logger to {@code DEBUG} and puts
 * back the level it found afterwards. The level is the JVM's, not the class's, and no key splits it
 * between tests the way a row's key does: a class doing the same beside another puts back a level
 * the other raised, mid-test, and the other's {@code DEBUG} lines are dropped. The records each test
 * reads stay filtered by names only it uses; the lock guards the level alone.
 *
 * <p>Annotate such a class {@code @ResourceLock(EncoderLoggerLevelLock.KEY)}.
 */
final class EncoderLoggerLevelLock {

    /** Annotate a class that sets the encoder logger's level {@code @ResourceLock(EncoderLoggerLevelLock.KEY)}. */
    static final String KEY = "node-id-encoder-logger-level";

    private EncoderLoggerLevelLock() {
    }
}
