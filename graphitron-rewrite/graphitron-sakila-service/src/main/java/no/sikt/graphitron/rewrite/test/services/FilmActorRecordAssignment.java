package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;

/**
 * R195 compilation-tier fixture: a {@code @service} input bean whose member is a jOOQ generated
 * {@link FilmActorRecord} backed by a <em>composite-key</em> table ({@code film_actor}, PK
 * {@code (actor_id, film_id)}). The backing SDL field carries {@code ID! @nodeId(typeName: "FilmActor")},
 * so the generated {@code decodeFilmActorRecord} helper materialises both key columns with one typed
 * {@code set} each. Exercises the composite-arity typed {@code set} against the real jOOQ catalog at
 * compile time.
 */
public record FilmActorRecordAssignment(FilmActorRecord filmActor) {
}
