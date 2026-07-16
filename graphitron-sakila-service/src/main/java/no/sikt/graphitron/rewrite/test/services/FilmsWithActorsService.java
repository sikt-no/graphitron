package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.ActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Execution-tier fixture: an {@code @service} mutation whose method returns a list of a
 * consumer-authored composite ({@link FilmWithActors}: one {@code FilmRecord} plus a
 * {@code List<ActorRecord>}), driving the two-level record-composite carrier through compile-spec and
 * execute-spec. The service hand-runs the SELECTs so the round-trip exercises the real shape: the
 * service returns {@code List<FilmWithActors>} → the carrier's data field
 * ({@code ChildField.RecordCompositeField}) projects them straight off {@code env.getSource()} (no
 * re-fetch) → graphql-java maps each composite onto the intermediate result type, whose
 * {@code @field}-mapped {@code @table} children ({@code film} / {@code actors}) re-fetch through the
 * record-backed accessor path off the composite's {@code filmRecord()} / {@code actorRecords()}.
 *
 * <p>A {@code filmId} of {@code -1} throws {@link IllegalArgumentException}, mapped by the payload's
 * {@code @error} channel: the {@code MutationServiceRecordField} try/catch ships
 * {@code Outcome.ErrorList}, so the data field renders {@code null} and the errors field is populated.
 */
public final class FilmsWithActorsService {

    private FilmsWithActorsService() {}

    public static List<FilmWithActors> create(DSLContext dsl, List<Integer> filmIds) {
        if (filmIds == null) return List.of();
        if (filmIds.contains(-1)) {
            throw new IllegalArgumentException("invalid film id: -1");
        }
        List<FilmWithActors> out = new ArrayList<>(filmIds.size());
        for (Integer filmId : filmIds) {
            FilmRecord film = dsl.selectFrom(Tables.FILM)
                .where(Tables.FILM.FILM_ID.eq(filmId))
                .fetchOne();
            if (film == null) continue;
            List<ActorRecord> actors = dsl.selectFrom(Tables.ACTOR)
                .where(Tables.ACTOR.ACTOR_ID.in(
                    dsl.select(Tables.FILM_ACTOR.ACTOR_ID)
                        .from(Tables.FILM_ACTOR)
                        .where(Tables.FILM_ACTOR.FILM_ID.eq(filmId))))
                .orderBy(Tables.ACTOR.ACTOR_ID)
                .fetch();
            out.add(new FilmWithActors(film, actors));
        }
        return out;
    }
}
