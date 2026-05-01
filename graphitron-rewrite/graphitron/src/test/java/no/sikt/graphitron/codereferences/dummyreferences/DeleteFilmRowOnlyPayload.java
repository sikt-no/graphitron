package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Test fixture record used by the DML payload-assembly classifier (R12 DML chunk) for the
 * shape where the payload exposes a row slot but no errors slot. Surfaces as
 * {@link no.sikt.graphitron.rewrite.model.PayloadAssembly} populated and
 * {@link no.sikt.graphitron.rewrite.model.ErrorChannel} empty: the success arm constructs the
 * payload, the catch arm falls back to {@code ErrorRouter.redact}.
 */
public record DeleteFilmRowOnlyPayload(FilmRecord film) {}
