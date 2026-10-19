package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Execution-tier fixture: {@code @service} producers whose returned {@code List<FilmRecord>} has
 * gaps, a {@code null} element or a record whose key has no live row. The carrier payload's list
 * field re-selects the records by primary key and leaves the gaps out, keeping the films that
 * exist in the producer's order.
 */
public final class FilmListGapService {

    private FilmListGapService() {}

    /**
     * MANY arm with gaps: one element per requested id, in input order, and {@code null} in the
     * position of an id the table has no row for. The carrier's list field drops the
     * {@code null} element and keeps the films that exist.
     */
    public static List<FilmRecord> filmsByIdsWithNulls(List<Integer> ids, DSLContext dsl) {
        var byPk = dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(ids))
            .fetchMap(Tables.FILM.FILM_ID);
        java.util.List<FilmRecord> ordered = new java.util.ArrayList<>(ids.size());
        for (Integer id : ids) ordered.add(byPk.get(id));
        return ordered;
    }

    /**
     * MANY arm as key carriers: one key-only {@code FilmRecord} per requested id, with no query
     * run, so an id the table has no row for is handed over as a key with no live row. The
     * carrier's list field drops that key and keeps the films that exist.
     */
    public static List<FilmRecord> filmsByIdsKeyOnly(List<Integer> ids, DSLContext dsl) {
        java.util.List<FilmRecord> carriers = new java.util.ArrayList<>(ids.size());
        for (Integer id : ids) {
            FilmRecord carrier = dsl.newRecord(Tables.FILM);
            carrier.setFilmId(id);
            carriers.add(carrier);
        }
        return carriers;
    }
}
