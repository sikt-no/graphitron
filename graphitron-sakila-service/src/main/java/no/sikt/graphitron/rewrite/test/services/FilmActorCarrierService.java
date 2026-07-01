package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * R158 composite-PK execution-tier fixture: an {@code @service}-backed mutation that
 * returns {@code List<FilmActorRecord>} verbatim, against a two-PK data table
 * ({@code film_actor}, PK {@code (actor_id, film_id)}). The carrier's data field classifies
 * as {@code ChildField.SingleRecordTableField} with
 * {@code SourceKey.Wrap.TableRecord(FilmActorRecord)}; the FetcherEmitter's
 * {@code Wrap.TableRecord} MANY arm emits the composite-PK paths
 * ({@code row(actor_id, film_id).in(...)} predicate +
 * {@code List.of(r.get(actor_id), r.get(film_id))} PK-keyed-map walk for R141 input-order
 * preservation) unique to multi-column keys.
 *
 * <p>Sibling to {@link FilmCarrierService}, which covers the single-PK shape.
 */
public final class FilmActorCarrierService {

    private FilmActorCarrierService() {}

    /**
     * MANY arm: returns film_actor rows for the given parallel {@code (actorId, filmId)} pairs,
     * in input order. Reads in one composite-PK SELECT, then walks the input pairs to project
     * the result so the producer's output order matches the caller's input pair order.
     */
    public static List<FilmActorRecord> filmActorsByKeys(
        List<Integer> actorIds, List<Integer> filmIds, DSLContext dsl
    ) {
        if (actorIds == null || filmIds == null || actorIds.isEmpty()) return List.of();
        if (actorIds.size() != filmIds.size()) {
            throw new IllegalArgumentException(
                "actorIds and filmIds must be parallel arrays of the same length"
            );
        }
        var pairs = new ArrayList<org.jooq.Row2<Integer, Integer>>(actorIds.size());
        for (int i = 0; i < actorIds.size(); i++) {
            pairs.add(DSL.row(actorIds.get(i), filmIds.get(i)));
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        var fetched = dsl.selectFrom(Tables.FILM_ACTOR)
            .where(DSL.row(Tables.FILM_ACTOR.ACTOR_ID, Tables.FILM_ACTOR.FILM_ID)
                .in(pairs.toArray(new org.jooq.Row2[0])))
            .fetch();
        Map<List<Integer>, FilmActorRecord> byPk = new HashMap<>(fetched.size());
        for (FilmActorRecord r : fetched) {
            byPk.put(
                List.of(r.get(Tables.FILM_ACTOR.ACTOR_ID), r.get(Tables.FILM_ACTOR.FILM_ID)),
                r
            );
        }
        var ordered = new ArrayList<FilmActorRecord>(actorIds.size());
        for (int i = 0; i < actorIds.size(); i++) {
            FilmActorRecord r = byPk.get(List.of(actorIds.get(i), filmIds.get(i)));
            if (r != null) ordered.add(r);
        }
        return ordered;
    }
}
