package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Test fixture record used by the DML payload-assembly classifier. The
 * canonical (all-fields) constructor exposes both a row slot ({@link FilmRecord}, the jOOQ
 * record class for the {@code film} table) and an errors slot typed as {@code List<Object>}.
 * Used as {@code @record(record: {className: "...DeleteFilmPayload"})} on a payload returned
 * by a {@code @mutation(typeName: DELETE)} field over the {@code film} table; exercises the
 * path where both {@link no.sikt.graphitron.rewrite.model.PayloadAssembly} and
 * {@link no.sikt.graphitron.rewrite.model.ErrorChannel} are populated.
 *
 * <p>The errors slot's {@code Object} element bound matches the source-direct contract on
 * {@code SakPayload}: the per-fetcher catch arm and the wrapper's pre-execution Jakarta
 * validation step push raw {@code Throwable}s and {@code GraphQLError}s into the list, so the
 * slot must admit both unrelated bounds.
 */
public record DeleteFilmPayload(FilmRecord film, List<Object> errors) {}
