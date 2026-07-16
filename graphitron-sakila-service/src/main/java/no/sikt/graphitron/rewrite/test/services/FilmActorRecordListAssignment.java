package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;

import java.util.List;

/**
 * Compilation-tier fixture: the both-dimensions corner, a {@code @service} input bean whose
 * member is a {@code List} of composite-key jOOQ generated {@link FilmActorRecord}s. The backing SDL
 * field carries {@code [ID!] @nodeId(typeName: "FilmActor")}, so the generated
 * {@code decodeFilmActorRecordList} helper wraps the composite-key per-element decode
 * ({@code decodeFilmActorRecord}). Exercises list-ness and composite arity at once against the real
 * jOOQ catalog at compile time.
 */
public record FilmActorRecordListAssignment(List<FilmActorRecord> filmActors) {
}
