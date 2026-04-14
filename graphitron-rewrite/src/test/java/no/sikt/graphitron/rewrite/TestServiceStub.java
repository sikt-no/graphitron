package no.sikt.graphitron.rewrite;

/**
 * Minimal service stub used by {@link GraphitronSchemaBuilderTest} to test that
 * {@code @service} fields are correctly classified when the service class exists
 * in the classpath.
 *
 * <p>The methods intentionally have no parameters so they work without the
 * {@code -parameters} compiler flag and without needing a specific {@code List<RowN>}
 * sources parameter.
 */
class TestServiceStub {

    /** Used for fields that require a no-arg service method named {@code get}. */
    public static String get() { throw new UnsupportedOperationException(); }

    /** Used for mutation fields that require a no-arg service method named {@code run}. */
    public static String run() { throw new UnsupportedOperationException(); }
}
