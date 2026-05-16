package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Test fixture record used by the carrier-walk classifier for a {@code @record}-wrapped DML
 * return whose wrapper exposes both a data channel and an errors channel. Used as
 * {@code @record(record: {className: "...DeleteFilmPayload"})} on a {@code @mutation(typeName:
 * DELETE)} field over the {@code film} table; admits via {@code BuildContext
 * .tryResolveSingleRecordCarrier} after R161 widening (JavaRecordType arm is now an admissible
 * carrier wrapper) and routes through {@code MutationDmlRecordField} with the error channel
 * wired via R12's LocalContext transport.
 *
 * <p>The {@code FilmRecord} component is informational metadata under R161: emit no longer
 * reads the developer class's row-slot type. The errors slot's {@code Object} element bound is
 * legacy: post-R161 the errors-channel data lives on {@code env.getLocalContext()} rather than
 * inside the payload class.
 */
public record DeleteFilmPayload(FilmRecord film, List<Object> errors) {}
