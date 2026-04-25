package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.Table;

import java.util.List;

/**
 * Fixture for service-backed and method-backed root fetchers (plan-service-root-fetchers.md).
 * Three methods, one per leaf:
 *
 * <ul>
 *   <li>{@link #popularFilms} — returns a {@code Table<FilmRecord>} for {@code @tableMethod};
 *       the framework wraps with a projection SELECT.</li>
 *   <li>{@link #filmsByService} — returns {@code Result<FilmRecord>} for {@code @service}
 *       with a {@code @table}-bound return type. Service hands records straight to graphql-java;
 *       no framework projection.</li>
 *   <li>{@link #filmCount} — returns a scalar for {@code @service} with a non-table return.</li>
 * </ul>
 *
 * <p>Lives in {@code graphitron-rewrite-fixtures} (not {@code graphitron-rewrite-test}) because
 * the rewrite generator runs during {@code generate-sources} of the test module and needs
 * this class on its classpath; fixtures compile first.
 */
public final class SampleQueryService {

    private SampleQueryService() {}

    /**
     * Filters the FILM table by minimum rental rate. The framework supplies
     * {@code Tables.FILM} as {@code filmTable} (the {@code ParamSource.Table} slot resolves
     * to {@code Tables.FILM} per the @table-bound return type) and projects via
     * {@code FilmType.$fields(...)} over the returned {@code Table<FilmRecord>}.
     */
    public static Table<FilmRecord> popularFilms(Table<FilmRecord> filmTable, Float minRentalRate) {
        return Tables.FILM.where(Tables.FILM.RENTAL_RATE.greaterOrEqual(
            org.jooq.impl.DSL.value(minRentalRate, Tables.FILM.RENTAL_RATE.getDataType())));
    }

    /**
     * Returns FilmRecords directly — no framework projection. Demonstrates that
     * {@code @service} with a {@code @table}-bound return type hands the records straight
     * to graphql-java, whose column fetchers walk them.
     */
    public static Result<FilmRecord> filmsByService(DSLContext dsl, List<Integer> ids) {
        return dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(ids))
            .orderBy(Tables.FILM.FILM_ID)
            .fetch();
    }

    /**
     * Returns a scalar {@code Integer} — graphql-java coerces to the GraphQL {@code Int!}.
     */
    public static Integer filmCount(DSLContext dsl) {
        return dsl.fetchCount(Tables.FILM);
    }
}
