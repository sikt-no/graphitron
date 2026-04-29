package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;
import org.jooq.Result;

import java.util.List;

/**
 * Fixture for service-backed and method-backed root fetchers. Three methods, one per leaf:
 *
 * <ul>
 *   <li>{@link #popularFilms} — returns a specific-typed {@link Film} for {@code @tableMethod};
 *       the framework wraps with a projection SELECT via {@code FilmType.$fields(...)}.</li>
 *   <li>{@link #filmsByService} — returns {@code Result<FilmRecord>} for {@code @service}
 *       with a {@code @table}-bound return type. Service hands records straight to graphql-java;
 *       no framework projection.</li>
 *   <li>{@link #filmCount} — returns a scalar for {@code @service} with a non-table return.</li>
 * </ul>
 *
 * <p>Lives in {@code graphitron-fixtures} (not {@code graphitron-test}) because
 * the rewrite generator runs during {@code generate-sources} of the test module and needs
 * this class on its classpath; fixtures compile first.
 */
public final class SampleQueryService {

    private SampleQueryService() {}

    /**
     * Filters the FILM table by minimum rental rate. The generated jOOQ {@code Film} class
     * overrides {@code where(...)} to return {@code Film} (not {@code Table<R>}), so the
     * filtered derived table preserves the specific table type required by the strict
     * {@code @tableMethod} return-type check in {@code ServiceCatalog.reflectTableMethod}, and
     * feeds {@code FilmType.$fields(...)} directly without a downcast. See
     * {@code rewrite-design-principles.md} ("Classifier guarantees shape emitter assumptions").
     */
    public static Film popularFilms(Film filmTable, Double minRentalRate) {
        return filmTable.where(filmTable.RENTAL_RATE.ge(java.math.BigDecimal.valueOf(minRentalRate)));
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

    /**
     * R41 fixture: same logic as {@link #filmsByService} but with the Java parameter named
     * {@code filmIds}, demonstrating {@code @field(name: "filmIds")} on a GraphQL argument named
     * {@code ids} that binds to a differently-named Java parameter.
     */
    public static Result<FilmRecord> filmsByServiceRenamed(DSLContext dsl, List<Integer> filmIds) {
        return dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(filmIds))
            .orderBy(Tables.FILM.FILM_ID)
            .fetch();
    }
}
