package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Fixture: free-form {@code @record} payload exposing a typed zero-arg accessor returning
 * {@code List<FilmRecord>}. The classifier auto-derives an accessor-derived batch key
 * from the {@code films()} accessor (record-canonical) and the {@code FilmRecord}'s mapped
 * jOOQ table; no {@code @sourceRow} directive needed.
 *
 * <p>Used by {@code AccessorDerivedSourceTest} alongside {@link CreateFilmsPayloadService}.
 * The schema's {@code CreateFilmsPayload.films: [Film!]!} field has no batching directive; the
 * generator is responsible for finding this accessor via reflection and emitting the
 * {@code loader.loadMany} dispatch path.
 */
public record CreateFilmsPayload(List<FilmRecord> films) {}
