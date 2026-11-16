package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

/**
 * An input bean whose members an ID cannot fill, each refused as {@code PARAMETER_MISMATCH}: a Long,
 * which an ID does not arrive as, and a film record, which an ID decodes into only through
 * {@code @nodeId(typeName:)}.
 */
public record FilmIdBean(Long filmId, FilmRecord film) {}
