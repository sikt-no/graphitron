package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;
import org.jooq.Result;

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
    /**
     * Returns the framework-supplied {@code Tables.FILM} aliased. Demonstrates that
     * {@code @tableMethod} preserves the developer's choice of jOOQ table while still
     * being projected by the framework via {@code FilmType.$fields(...)}. The minimum
     * rental rate argument is intentionally unused in this fixture (filtering on a
     * specific table class isn't directly possible since {@code Tables.FILM.where(...)}
     * returns a wider {@code Table<R>} that would fail the classifier-time return-type
     * check — see plan-service-root-fetchers.md Invariants §3); the arg is kept on the
     * SDL so the {@code ParamSource.Arg} slot is exercised in declaration order. Real
     * use cases for {@code @tableMethod}-with-strict-return are tenant-scoped table
     * routing or aliasing — both of which preserve the {@code Film} type.
     */
    public static Film popularFilms(Film filmTable, Double minRentalRate) {
        return filmTable.as("popular_films");
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
