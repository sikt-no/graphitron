package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.ActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * A consumer-authored composite record bundling several jOOQ table records: one
 * {@link FilmRecord} plus a {@code List<}{@link ActorRecord}{@code >}. The Sakila-catalog analog
 * of the driving utdanningsregisteret shape (one {@code UtdanningsspesifikasjonRecord} plus a
 * {@code List<UtdanningsmulighetRecord>}). Returned by the {@code @service} producer stubs
 * ({@link TestServiceStub#createFilmWithActors} / {@link TestServiceStub#createFilmsWithActors})
 * so the two-level carrier
 * {@code Payload { result(s): Result(s) , errors }} / {@code Result { film: Film, actors: [Actor] }}
 * grounds {@code Result} to this class on the result axis and projects the producer's record(s)
 * through the payload's data field.
 *
 * <p>The component names ({@code filmRecord} / {@code actorRecords}) diverge from the SDL field
 * names ({@code film} / {@code actors}); the SDL bridges the divergence with {@code @field(name:)},
 * exercising the record-backed accessor path for the composite's {@code @table} children.
 */
public record TestFilmWithActorsDto(FilmRecord filmRecord, List<ActorRecord> actorRecords) {}
