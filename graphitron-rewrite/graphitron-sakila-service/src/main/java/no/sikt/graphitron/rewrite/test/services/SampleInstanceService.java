package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import org.jooq.DSLContext;

/**
 * R87 Phase D fixture: an instance-method {@code @service} class with a
 * {@code public (DSLContext)} constructor. Drives the
 * {@code service-catalog-instance-service-holder-shape} {@code @LoadBearingClassifierCheck}
 * end-to-end at the compile tier — a regression in {@code TypeFetcherGenerator#serviceCallTarget}
 * that mistakenly emitted the static {@code SampleInstanceService.titleByFilmId(...)} call shape
 * (against this instance method) would surface as a {@code javac} rejection on the generated
 * source, not as a body-string mismatch in a unit test.
 *
 * <p>Lives in {@code graphitron-sakila-service} alongside {@link SampleQueryService} so the
 * generator running in {@code graphitron-sakila-example}'s {@code generate-sources} phase has the
 * class on its classpath.
 */
public class SampleInstanceService {

    private final DSLContext ctx;

    public SampleInstanceService(DSLContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Looks up a film's title by its primary key. Instance method — the framework constructs a
     * fresh {@code SampleInstanceService(dsl)} per fetcher invocation and dispatches via
     * {@code new SampleInstanceService(dsl).titleByFilmId(filmId)}.
     */
    public String titleByFilmId(Integer filmId) {
        return ctx.select(Tables.FILM.TITLE)
            .from(Tables.FILM)
            .where(Tables.FILM.FILM_ID.eq(filmId))
            .fetchOneInto(String.class);
    }
}
