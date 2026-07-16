package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Fixture: a {@code @service} input bean whose member is a {@code List} of jOOQ
 * {@code *Record}s. Mirrors a {@code [ID!] @nodeId(typeName: "Film")} SDL field so the classifier
 * decodes each wire-format NodeId into a {@link FilmRecord} via the list helper variant
 * ({@code decodeFilmRecordList}, which delegates to {@code decodeFilmRecord} per element) instead of
 * casting the wire {@code List<String>} to {@code List<FilmRecord>}.
 */
public record TestNodeIdRecordListBean(List<FilmRecord> films) {
}
