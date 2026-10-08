package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;
import org.jooq.DSLContext;
import org.jooq.Row1;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service fixtures for the {@code @service} tenant-binding classification tests
 * ({@link TenantBindingClassificationTest}): roots whose arguments carry a tenant-scoped record
 * through the shapes a service parameter can take, and child services that bind a connection
 * under a tenant-bound parent. Bodies never run.
 */
class TenantServiceStub {

    /** Bean member typed as a node table's record, decoded from {@code @nodeId}. */
    public static TenantFilmsPayload rateFilm(TestNodeIdRecordBean in) {
        throw new UnsupportedOperationException();
    }

    /** {@link #rateFilm} with a {@code DSLContext} parameter, so the call declares {@code dsl}. */
    public static TenantFilmsPayload rateFilmWithDsl(DSLContext dsl, TestNodeIdRecordBean in) {
        throw new UnsupportedOperationException();
    }

    /** {@link #rateFilmWithDsl} returning an error-channel payload. */
    public static no.sikt.graphitron.codereferences.dummyreferences.SakPayload rateFilmOutcome(
            DSLContext dsl, TestNodeIdRecordBean in) {
        throw new UnsupportedOperationException();
    }

    /** The batch shape: a list of beans, each naming a film. */
    public static TenantFilmsPayload rateFilms(List<TestNodeIdRecordBean> ratings) {
        throw new UnsupportedOperationException();
    }

    /** Two parameters whose members both end in {@code film}. */
    public static TenantFilmsPayload rateTwo(TestNodeIdRecordBean first, TestNodeIdRecordBean second) {
        throw new UnsupportedOperationException();
    }

    /** One bean with two tenant-bearing members. */
    public static TenantFilmsPayload ratePair(TenantFilmPairBean in) {
        throw new UnsupportedOperationException();
    }

    /** A jOOQ record parameter, bound on the column axis. */
    public static TenantFilmsPayload modifyFilms(List<FilmRecord> in) {
        throw new UnsupportedOperationException();
    }

    /** A composite-key jOOQ record parameter, the tenant at key position 1. */
    public static TenantFilmsPayload modifyFilmActor(FilmActorRecord in) {
        throw new UnsupportedOperationException();
    }

    /** A top-level {@code @nodeId} argument decoding to the record. */
    public static TenantFilmsPayload rateById(FilmRecord film) {
        throw new UnsupportedOperationException();
    }

    /** An undecoded {@code ID}: the build has no node type to read. */
    public static TenantFilmsPayload rateByRawId(String film) {
        throw new UnsupportedOperationException();
    }

    /** {@link #rateByRawId} handed a connection: nothing routes it, so it would run on the default source. */
    public static TenantFilmsPayload rateByRawIdOnConnection(DSLContext dsl, String film) {
        throw new UnsupportedOperationException();
    }

    /** A table-returning root over a tenant-scoped table, handed a connection, naming no tenant. */
    public static FilmRecord pickFilmOnConnection(DSLContext dsl, String film) {
        throw new UnsupportedOperationException();
    }

    /** A tenant-scoped table's record bound only to a non-tenant column: tenant evidence. */
    public static String retitleFilm(DSLContext dsl, FilmRecord in) {
        throw new UnsupportedOperationException();
    }

    /** A polymorphic record member at an empty-reach root handed a connection. */
    public static String pickForThing(DSLContext dsl, TestNodeIdPolymorphicRecordBean in) {
        throw new UnsupportedOperationException();
    }

    /** {@link #rateFilms} whose payload also carries a global row, re-projected on its own coordinate. */
    public static TenantFilmsLanguagePayload rateFilmsWithLanguage(List<TestNodeIdRecordBean> ratings) {
        throw new UnsupportedOperationException();
    }

    /** A connection-bound root over global data whose payload carries a global row. */
    public static GlobalLanguagePayload globalLanguage(DSLContext dsl) {
        throw new UnsupportedOperationException();
    }

    /** A root handed a connection with no arguments at all, as a service over global data is. */
    public static Boolean refreshLanguages(DSLContext dsl) {
        throw new UnsupportedOperationException();
    }

    /** A table-returning root over a global table, handed a connection. */
    public static LanguageRecord languageOnConnection(DSLContext dsl) {
        throw new UnsupportedOperationException();
    }

    /** A table-returning root over a tenant-scoped table. */
    public static FilmRecord pickFilm(TestNodeIdRecordBean in) {
        throw new UnsupportedOperationException();
    }

    /** A table-returning root over a global table, taking a tenant's record. */
    public static LanguageRecord languageOfFilm(TestNodeIdRecordBean in) {
        throw new UnsupportedOperationException();
    }

    /** A polymorphic record member at a table-returning root. */
    public static FilmRecord pickFilmForThing(TestNodeIdPolymorphicRecordBean in) {
        throw new UnsupportedOperationException();
    }

    /** A polymorphic record member beside a divining one. */
    public static TenantFilmsPayload rateFilmAndThing(TenantFilmAndThingBean in) {
        throw new UnsupportedOperationException();
    }

    /** A bean whose tenant carrier is a plain scalar, handed a connection. */
    public static TenantFilmsPayload rateByFilmId(DSLContext dsl, RateByFilmIdBean in) {
        throw new UnsupportedOperationException();
    }

    /** {@link #rateByFilmId} over the Java record twin. */
    public static TenantFilmsPayload rateByFilmIdRecord(DSLContext dsl, RateByFilmIdRecord in) {
        throw new UnsupportedOperationException();
    }

    /** {@link #rateByFilmId}'s batch shape. */
    public static TenantFilmsPayload rateByFilmIds(DSLContext dsl, List<RateByFilmIdBean> in) {
        throw new UnsupportedOperationException();
    }

    /** The tenant carrier as a top-level scalar argument. */
    public static TenantFilmsPayload rateByFilmIdArgument(DSLContext dsl, Integer filmId) {
        throw new UnsupportedOperationException();
    }

    /** A {@code String} scalar against the {@code Integer} tenant key. */
    public static TenantFilmsPayload rateByFilmCode(DSLContext dsl, RateByFilmCodeBean in) {
        throw new UnsupportedOperationException();
    }

    /** A plain scalar beside a decoded {@code @nodeId} member. */
    public static TenantFilmsPayload rateByFilmIdAndFilm(DSLContext dsl, RateByFilmIdAndFilmBean in) {
        throw new UnsupportedOperationException();
    }

    /** A table-returning root over a global table, taking a plain scalar. */
    public static LanguageRecord languageByFilmId(DSLContext dsl, RateByFilmIdBean in) {
        throw new UnsupportedOperationException();
    }

    /** Child: a static method taking a {@code DSLContext}. */
    public static Map<Row1<Integer>, String> ratingWithDsl(Set<Row1<Integer>> keys, DSLContext dsl) {
        throw new UnsupportedOperationException();
    }

    /** Child: binds no connection. */
    public static Map<Row1<Integer>, String> ratingPlain(Set<Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /** Child returning a global table, taking a {@code DSLContext}. */
    public static Map<Row1<Integer>, LanguageRecord> languageWithDsl(Set<Row1<Integer>> keys, DSLContext dsl) {
        throw new UnsupportedOperationException();
    }

    /** Child returning a global table, binding {@code $session}. */
    public static Map<Row1<Integer>, LanguageRecord> languageForSession(
            Set<Row1<Integer>> keys, DSLContext dsl, String identity) {
        throw new UnsupportedOperationException();
    }
}
