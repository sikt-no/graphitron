package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * R195 compilation-tier fixture: a {@code @service} input bean whose member is a {@code List} of
 * jOOQ generated {@link FilmRecord}s. The backing SDL field carries
 * {@code [ID!] @nodeId(typeName: "Film")}, so the generated {@code decodeFilmRecordList} helper
 * streams the wire {@code List<String>} and materialises one {@link FilmRecord} per element via the
 * singular {@code decodeFilmRecord} helper. Exercises the list-variant return type ({@code List<…>})
 * against the real jOOQ catalog at compile time.
 */
public record FilmRecordListAssignment(List<FilmRecord> films) {
}
