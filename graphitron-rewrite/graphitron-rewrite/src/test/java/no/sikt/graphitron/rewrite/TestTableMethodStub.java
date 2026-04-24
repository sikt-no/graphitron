package no.sikt.graphitron.rewrite;

/**
 * Minimal table-method stub used by {@link GraphitronSchemaBuilderTest} to test that
 * {@code @tableMethod} fields are correctly classified when the method class exists on the classpath.
 *
 * <p>The {@code get} method takes only a {@code Table<?>} parameter so it works without any
 * GraphQL arguments or context arguments. The {@code getWithContext} method adds a {@code String}
 * context argument to verify that context parameters are correctly reflected into
 * {@link no.sikt.graphitron.rewrite.model.ParamSource.Context}.
 *
 * <p>Requires the {@code -parameters} compiler flag for {@code getWithContext} (the project's
 * {@code pom.xml} already sets this flag).
 */
class TestTableMethodStub {

    /** Table method with no additional parameters. */
    public static org.jooq.Table<?> get(org.jooq.Table<?> table) {
        throw new UnsupportedOperationException();
    }

    /** Table method with a context argument — used to test context-argument reflection. */
    public static org.jooq.Table<?> getWithContext(org.jooq.Table<?> table, String tenantId) {
        throw new UnsupportedOperationException();
    }
}
