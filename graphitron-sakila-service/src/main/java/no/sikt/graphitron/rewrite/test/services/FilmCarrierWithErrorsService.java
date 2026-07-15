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
 * as a carrier data field whose {@code SourceEnvelope} is
 * {@code OUTCOME_SUCCESS}: its generated fetcher narrows {@code Outcome.Success}, reads
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

    /**
     * R275 requirement-2 fixture, single arm (the opptak {@code fjernSakTagg -> { taggId: ID
     * @nodeId, errors }} shape): a delete-shaped service whose payload data field is an
     * {@code ID @nodeId(typeName: "Film")} scalar encoded straight off the returned record's
     * key columns, with no follow-up SELECT. The method deliberately synthesizes the
     * {@code FilmRecord} in memory without touching the database, so ids that do not exist in
     * any table (e.g. 9001) still encode; a generated re-fetch would resolve null and fail the
     * execution test. id 999 throws the mapped "missing film" {@code @error} (the
     * {@code Outcome.ErrorList} arm).
     */
    public static FilmRecord deleteFilmById(Integer id, DSLContext dsl) {
        if (id == null || id < 1) {
            throw new FilmReviewBadRatingException("id must be >= 1; got " + id);
        }
        if (id == 999) {
            throw new FilmReviewMissingFilmException("film " + id + " not found");
        }
        FilmRecord deleted = new FilmRecord();
        deleted.set(Tables.FILM.FILM_ID, id);
        return deleted;
    }

    /**
     * R275 requirement-2 fixture, list arm (the opptak {@code fjernSakTagger -> { tagger: [ID]
     * @nodeId, errors }} shape): the MANY-cardinality sibling of {@link #deleteFilmById}.
     * Returns synthesized records in input order; id 999 anywhere in the list throws the mapped
     * "missing film" {@code @error}.
     */
    public static java.util.List<FilmRecord> deleteFilmsByIds(java.util.List<Integer> ids, DSLContext dsl) {
        if (ids == null || ids.isEmpty()) {
            throw new FilmReviewBadRatingException("ids must be non-empty");
        }
        java.util.List<FilmRecord> deleted = new java.util.ArrayList<>(ids.size());
        for (Integer id : ids) {
            deleted.add(deleteFilmById(id, dsl));
        }
        return deleted;
    }

    /**
     * R275 reopened-scope list arm (the opptak {@code leggTilTagger -> { saker: [Sak!]
     * @splitQuery, errors }} shape): returns the films with the given ids in input order, or
     * throws the mapped "missing film" {@code @error} when any id is unknown (exercises the
     * {@code Outcome.ErrorList} arm over a MANY-cardinality carrier: the data field renders
     * null, the typed error lands in the errors union). The happy path exercises the
     * {@code OUTCOME_SUCCESS} MANY arm: the data field's fetcher narrows the concrete
     * {@code Outcome.Success<List<FilmRecord>>}, reads {@code success.value()}, and
     * re-selects the requested columns by PK preserving input order.
     */
    public static java.util.List<FilmRecord> filmsByIds(java.util.List<Integer> ids, DSLContext dsl) {
        if (ids == null || ids.isEmpty()) {
            throw new FilmReviewBadRatingException("ids must be non-empty");
        }
        var fetched = dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(ids))
            .fetch();
        java.util.Map<Integer, FilmRecord> byPk = new java.util.HashMap<>(fetched.size());
        for (FilmRecord r : fetched) byPk.put(r.get(Tables.FILM.FILM_ID), r);
        java.util.List<FilmRecord> ordered = new java.util.ArrayList<>(ids.size());
        for (Integer id : ids) {
            FilmRecord r = byPk.get(id);
            if (r == null) {
                throw new FilmReviewMissingFilmException("film " + id + " not found");
            }
            ordered.add(r);
        }
        return ordered;
    }
}
