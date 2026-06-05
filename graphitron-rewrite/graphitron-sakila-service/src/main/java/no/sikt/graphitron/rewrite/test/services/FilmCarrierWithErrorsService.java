package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;

/**
 * R275 execution-tier fixture: the source-record carrier shape. An {@code @service} mutation
 * whose method returns a bare jOOQ {@code FilmRecord} (not a {@code @record} payload object)
 * into a payload pairing a {@code @table}-bound data field ({@code film}) with an {@code errors}
 * union field. This is the opptak {@code { sak: Sak, errors: [...] }} shape distilled onto sakila.
 *
 * <p>Because the payload carries an errors field, the producer routes the returned record through
 * the typed {@code Outcome} wrapper ({@code Outcome.Success(filmRecord)} on the happy path,
 * {@code Outcome.ErrorList} on a mapped {@code @error} throw). The data field therefore classifies
 * as {@code ChildField.SingleRecordTableField} with {@code SourceKey.Reader.ResultRowWalk} of
 * envelope {@code OUTCOME_SUCCESS}: its generated fetcher narrows {@code Outcome.Success}, reads
 * the record off {@code success.value()}, and resolves null on the {@code ErrorList} arm. Before
 * R275 it cast {@code env.getSource()} (an {@code Outcome}) straight to {@code FilmRecord} and threw
 * a {@code ClassCastException}.
 */
public final class FilmCarrierWithErrorsService {

    private FilmCarrierWithErrorsService() {}

    /**
     * Returns the film with the given id. id 999 throws the mapped "missing film" {@code @error}
     * (exercises the {@code Outcome.ErrorList} arm: the data field renders null, the typed error
     * lands in the errors union); any other id resolves the bare {@code FilmRecord} (the
     * {@code Outcome.Success} arm: the data field re-selects the full row off {@code success.value()}).
     */
    public static FilmRecord filmById(Integer id, DSLContext dsl) {
        if (id == null || id < 1) {
            throw new FilmReviewBadRatingException("id must be >= 1; got " + id);
        }
        if (id == 999) {
            throw new FilmReviewMissingFilmException("film " + id + " not found");
        }
        return dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.eq(id))
            .fetchOne();
    }
}
