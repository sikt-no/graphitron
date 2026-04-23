package no.sikt.graphitron.rewrite;

import org.jooq.DSLContext;

/**
 * Minimal service stub used by {@link GraphitronSchemaBuilderTest} to test that
 * {@code @service} fields are correctly classified when the service class exists
 * in the classpath.
 *
 * <p>The no-arg methods intentionally take no parameters so they work without the
 * {@code -parameters} compiler flag and without needing a specific {@code List<RowN>}
 * sources parameter. The {@code *WithDsl} methods exercise the
 * {@link org.jooq.DSLContext}-parameter classification branch in
 * {@link ServiceCatalog#reflectServiceMethod}.
 */
class TestServiceStub {

    /** Used for fields that require a no-arg service method named {@code get}. */
    public static String get() { throw new UnsupportedOperationException(); }

    /** Used for mutation fields that require a no-arg service method named {@code run}. */
    public static String run() { throw new UnsupportedOperationException(); }

    /** Service method with a {@link DSLContext} parameter and no other params. */
    public static String getWithDsl(DSLContext dsl) { throw new UnsupportedOperationException(); }

    /** Service method with a {@link DSLContext} parameter followed by a GraphQL argument. */
    public static String getByIdWithDsl(DSLContext dsl, String id) { throw new UnsupportedOperationException(); }

    /** Service method with a {@link DSLContext} parameter whose name collides with a GraphQL argument. */
    public static String getFilteredWithDsl(DSLContext filter) { throw new UnsupportedOperationException(); }

    /** Service method with a parameter that isn't DSLContext, isn't a GraphQL arg, and isn't a {@code List<?>}. */
    public static String getWithUnknown(Object opaque) { throw new UnsupportedOperationException(); }
}
