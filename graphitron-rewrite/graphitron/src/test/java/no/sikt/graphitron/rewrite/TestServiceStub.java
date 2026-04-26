package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;
import org.jooq.DSLContext;
import org.jooq.Result;

/**
 * Minimal service stub used by {@link GraphitronSchemaBuilderTest} to test that
 * {@code @service} fields are correctly classified when the service class exists
 * in the classpath.
 *
 * <p>The {@code String}-returning methods are kept for tests that don't care about
 * the field's resolved return type (parameter-classification tests, return-type
 * mismatch negative cases). Methods returning specific jOOQ record classes
 * ({@link FilmRecord}, {@link LanguageRecord}, etc.) exercise the strict
 * service-return-type validation in {@code ServiceCatalog.reflectServiceMethod}
 * against {@code FieldBuilder.computeExpectedServiceReturnType}: a {@code @service}
 * field whose resolved return type is {@code @table}-bound (or a {@code @record}
 * with a backing class) requires the developer's method to declare a matching
 * parameterized return type.
 *
 * <p>Requires the {@code -parameters} compiler flag for context-arg variants
 * (the project's {@code pom.xml} already sets this flag).
 */
class TestServiceStub {

    // ===== String-returning methods (return-type mismatch / parameter-classification tests) =====

    /** No-arg, returns {@code String} — used for parameter-classification tests. */
    public static String get() { throw new UnsupportedOperationException(); }

    /** No-arg, returns {@code String} — used for mutation parameter-classification tests. */
    public static String run() { throw new UnsupportedOperationException(); }

    /** {@link DSLContext} param, returns {@code String}. */
    public static String getWithDsl(DSLContext dsl) { throw new UnsupportedOperationException(); }

    /** {@link DSLContext} + GraphQL arg, returns {@code String}. */
    public static String getByIdWithDsl(DSLContext dsl, String id) { throw new UnsupportedOperationException(); }

    /** {@link DSLContext} whose name collides with a GraphQL argument. */
    public static String getFilteredWithDsl(DSLContext filter) { throw new UnsupportedOperationException(); }

    /** Param that isn't DSLContext, isn't a GraphQL arg, and isn't a {@code List<?>}. */
    public static String getWithUnknown(Object opaque) { throw new UnsupportedOperationException(); }

    // ===== FilmRecord-returning methods (positive-classification tests with @table-bound returns) =====

    /** Returns the specific {@link FilmRecord} — used by {@code @service} on a {@code Film} return. */
    public static FilmRecord getFilm() { throw new UnsupportedOperationException(); }

    /** Returns the specific {@link FilmRecord} — used by {@code @service} on a mutation Film return. */
    public static FilmRecord runFilm() { throw new UnsupportedOperationException(); }

    /** Returns a list of {@link FilmRecord} — used by {@code @service} on a {@code [Film!]!} return. */
    public static Result<FilmRecord> getFilms() { throw new UnsupportedOperationException(); }

    /**
     * Returns {@link FilmRecord} but takes a {@code List<Row1<Integer>>} parameter — classifies
     * as {@link no.sikt.graphitron.rewrite.model.ParamSource.Sources}. Used to verify
     * Invariants §2 fires after the strict-return-type check passes.
     */
    public static FilmRecord getFilmWithSources(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a list of {@link FilmRecord} but takes a {@code List<Row1<Integer>>} parameter — used
     * for tests that target root-level {@code @service} on {@code [Film!]!} with a Sources param.
     */
    public static Result<FilmRecord> getFilmsWithSources(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    // ===== LanguageRecord-returning methods (for child @service on FilmDetails @record) =====

    /** Returns {@link LanguageRecord} — used by {@code @service} on a {@code Language} return. */
    public static LanguageRecord getLanguage() { throw new UnsupportedOperationException(); }

    /**
     * Returns {@code Result<LanguageRecord>} — used to exercise the inner-generic-mismatch
     * rejection path where a {@code [Film!]!} field expecting {@code Result<FilmRecord>} is
     * pointed at a method whose outer type matches but whose inner type does not.
     */
    public static Result<LanguageRecord> getLanguages() { throw new UnsupportedOperationException(); }

    // ===== Methods that intentionally violate return-type strictness =====

    /**
     * Service method with a {@code List<Row1<Integer>>} parameter and a String return.
     * Used to verify the strict-return-type rejection path; if the method also took a Sources
     * param it would still be the strict-return-type rejection that fires first. For Sources-
     * specific tests use {@link #getFilmWithSources}.
     */
    public static String getWithSources(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    // ===== Sources classification tests =====

    /**
     * Takes a {@code List<FilmRecord>} — a jOOQ {@code TableRecord} subtype. Used to verify
     * that a {@code TableRecord} element type classifies as {@link no.sikt.graphitron.rewrite.model.BatchKey.RowKeyed}.
     */
    public static Result<FilmRecord> getFilmsWithTableRecordSources(java.util.List<FilmRecord> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code List<TestDtoStub>} — a plain class with no jOOQ semantics. Used to verify
     * that a non-{@code TableRecord} element type is rejected with a DTO-sources error.
     */
    public static Result<FilmRecord> getFilmsWithDtoSources(java.util.List<TestDtoStub> keys) {
        throw new UnsupportedOperationException();
    }
}
