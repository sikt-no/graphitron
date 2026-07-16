package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Execution-tier fixture: an {@code @service}-backed mutation that returns the
 * payload's data field shape verbatim ({@code List<FilmRecord>} for MANY,
 * {@code FilmRecord} for ONE). The carrier's data field classifies as
 * {@code ChildField.SingleRecordTableField} with
 * {@code SourceKey.Wrap.TableRecord(FilmRecord)}; the FetcherEmitter's
 * {@code Wrap.TableRecord} arm reads the typed records, extracts PKs via
 * {@code record.get(Tables.FILM.FILM_ID)}, and runs the response SELECT outside the
 * service call.
 *
 * <p>The service hand-runs a SELECT against the database so the execution test exercises
 * the real round-trip: service returns records → data-field fetcher casts to
 * {@code List<FilmRecord>} → response SELECT runs against the requested columns →
 * graphql-java traverses the result.
 */
public final class FilmCarrierService {

    private FilmCarrierService() {}

    /**
     * MANY arm: returns films with the given IDs, in input order. Reads the rows in one
     * SELECT and walks the input order to project the result, so the producer's output
     * order matches the input array. The data-field fetcher then re-projects them through
     * the response SELECT (one-shot full-Film read against the received PKs), preserving
     * the producer's input-aligned order via the PK-keyed-map indirection.
     */
    public static List<FilmRecord> filmsByIds(List<Integer> ids, DSLContext dsl) {
        if (ids == null || ids.isEmpty()) return List.of();
        var fetched = dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(ids))
            .fetch();
        java.util.Map<Integer, FilmRecord> byPk = new java.util.HashMap<>(fetched.size());
        for (FilmRecord r : fetched) byPk.put(r.get(Tables.FILM.FILM_ID), r);
        java.util.List<FilmRecord> ordered = new java.util.ArrayList<>(ids.size());
        for (Integer id : ids) {
            FilmRecord r = byPk.get(id);
            if (r != null) ordered.add(r);
        }
        return ordered;
    }

    /** ONE arm: returns a single film by its ID, or {@code null} when the ID is unknown. */
    public static FilmRecord filmById(Integer id, DSLContext dsl) {
        if (id == null) return null;
        return dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.eq(id))
            .fetchOne();
    }
}
