package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.Actor;
import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import no.sikt.graphitron.rewrite.test.jooq.tables.Inventory;
import no.sikt.graphitron.rewrite.test.jooq.tables.Language;

/**
 * Minimal table-method stub used by {@link GraphitronSchemaBuilderTest} to test that
 * {@code @tableMethod} fields are correctly classified when the method class exists on the classpath.
 *
 * <p>Each method's return type is the specific generated jOOQ table class (e.g. {@link Film},
 * {@link Language}, {@link Actor}); the classifier-time return-type strictness check in
 * {@code ServiceCatalog.reflectTableMethod} requires this. The {@code Table<?>}-returning
 * methods further down ({@link #get}, {@link #getWithContext}) intentionally violate that
 * rule and exist solely so tests can assert the rejection path.
 *
 * <p>After R43 the {@code @tableMethod} contract no longer accepts a parent or return-type
 * Table parameter: graphitron derives the target table from the method's return type and
 * parent-table filtering is {@code @reference}'s job. Methods that need GraphQL-driven
 * filtering declare regular parameters bound via {@code argMapping}.
 *
 * <p>Requires the {@code -parameters} compiler flag for context-arg variants (the project's
 * {@code pom.xml} already sets this flag).
 */
class TestTableMethodStub {

    /** Returns {@link Film} — for {@code @tableMethod} on a {@code Film} return type. */
    public static Film getFilm() {
        throw new UnsupportedOperationException();
    }

    /** Returns {@link Film} with a context argument. */
    public static Film getFilmWithContext(String tenantId) {
        throw new UnsupportedOperationException();
    }

    /** Returns {@link Language} — for {@code @tableMethod} on a {@code Language} return type. */
    public static Language getLanguage() {
        throw new UnsupportedOperationException();
    }

    /** Returns {@link Language} with a context argument — used to test context-arg reflection. */
    public static Language getLanguageWithContext(String tenantId) {
        throw new UnsupportedOperationException();
    }

    /** Returns {@link Actor} — for {@code @tableMethod} on an {@code Actor} (or list/connection) return type. */
    public static Actor getActor() {
        throw new UnsupportedOperationException();
    }

    /** Returns {@link Inventory} — for {@code @tableMethod} tests targeting Inventory (single-FK auto-derive cases on Film). */
    public static Inventory getInventory() {
        throw new UnsupportedOperationException();
    }

    /**
     * Wider return type ({@code Table<?>}). Violates Invariants §3; existing tests that used
     * {@code "get"} on a specific @table-bound return get the strict-return-type rejection.
     * Kept as a fixture so the rejection path can be asserted directly.
     */
    public static org.jooq.Table<?> get() {
        throw new UnsupportedOperationException();
    }

    /** Wider return type with a context argument. */
    public static org.jooq.Table<?> getWithContext(String tenantId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Instance method (no {@code static} modifier) — exists to provoke the
     * static-only rejection arm in {@code ServiceCatalog.reflectTableMethod} (the
     * {@code MethodRef.StaticOnly} sealed variant requires a static method). Used by
     * {@code TableMethodFieldValidationTest.instanceTableMethod_validatorReportsAuthorError}.
     */
    public Film getFilmInstance() {
        throw new UnsupportedOperationException();
    }
}
