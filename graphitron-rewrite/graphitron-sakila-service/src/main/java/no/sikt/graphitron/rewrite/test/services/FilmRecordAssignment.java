package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * R195 compilation-tier fixture: a {@code @service} input bean whose member is a jOOQ generated
 * {@link FilmRecord}. The backing SDL field carries {@code ID! @nodeId(typeName: "Film")}, so the
 * generated {@code create<Bean>} helper must decode the wire-format NodeId into a {@link FilmRecord}
 * (via {@code NodeIdEncoder.decodeFilm} + {@code FilmRecord.from(key)}) rather than casting the wire
 * {@code String} to {@link FilmRecord}. Exercises {@code FilmRecord.from(Record)} against the real
 * jOOQ catalog at compile time.
 */
public record FilmRecordAssignment(FilmRecord film) {
}
