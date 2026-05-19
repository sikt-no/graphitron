package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Test fixture record used by the @mutation classifier for a {@code @record}-wrapped DML
 * return whose wrapper exposes a data channel only (no errors channel). Used as
 * {@code @record(record: {className: "...DeleteFilmRowOnlyPayload"})} on a {@code @mutation
 * (typeName: DELETE)}.
 */
public record DeleteFilmRowOnlyPayload(FilmRecord film) {}
