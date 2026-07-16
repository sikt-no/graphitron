package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Fixture: a consumer-authored {@code @service} input bean whose member is a jOOQ generated
 * {@code *Record}. Mirrors the reported shape (a bean field typed as a {@code *Record} backed by an
 * {@code ID! @nodeId(typeName:)} SDL field) so the classifier decodes the wire-format NodeId into
 * the record instead of casting the wire {@code String} to {@link FilmRecord} (the
 * {@code ClassCastException}).
 */
public record TestNodeIdRecordBean(FilmRecord film) {
}
