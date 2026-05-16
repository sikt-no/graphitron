package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Test fixture record used by the carrier-walk classifier for a {@code @record}-wrapped DML
 * return whose wrapper exposes a data channel only (no errors channel). Used as
 * {@code @record(record: {className: "...DeleteFilmRowOnlyPayload"})} on a {@code @mutation
 * (typeName: DELETE)}; admits via {@code BuildContext.tryResolveSingleRecordCarrier} after
 * R161 widening (JavaRecordType arm is now an admissible carrier wrapper).
 */
public record DeleteFilmRowOnlyPayload(FilmRecord film) {}
