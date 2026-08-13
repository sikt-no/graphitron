package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Execution-tier fixture: a type aggregated in Java that hosts a batched child {@code @service}.
 *
 * <p>The batch key is not this record's own identity, it is the {@code film} record it holds: the
 * child's {@code Set<FilmRecord>} parameter names the {@code film} table, and this class produces one
 * through the sole zero-arg accessor returning that record type. {@code label} is here so the class
 * has more than one accessor and only the record-returning one can qualify, and so the SDL type has an
 * ordinary read alongside the service child.
 *
 * <p>The accessor is deliberately <em>not</em> named after the SDL field the {@code @service} sits on.
 * The element type names the key on this path; a name match would be a coincidence, and the resolution
 * must not depend on one.
 */
public record FilmKeySummary(FilmRecord film, String label) {}
