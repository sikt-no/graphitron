package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Compilation-tier fixture: a {@code @service} input bean whose member is a jOOQ generated
 * {@link FilmRecord}. The backing SDL field carries {@code ID! @nodeId(typeName: "Film")}, so the
 * generated {@code decodeFilmRecord} helper must decode the wire-format NodeId into a
 * {@link FilmRecord} (via {@code NodeIdEncoder.decodeValues} + a typed
 * {@code record.set(Tables.FILM.FILM_ID, …convert(values[0]))}) rather than casting the wire
 * {@code String} to {@link FilmRecord}. Exercises the typed {@code Record.set(Field<T>, T)} against
 * the real jOOQ catalog at compile time.
 */
public record FilmRecordAssignment(FilmRecord film) {
}
