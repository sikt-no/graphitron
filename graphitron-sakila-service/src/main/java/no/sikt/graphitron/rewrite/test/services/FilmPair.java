package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

/**
 * Fixture for a key row that cannot be told: two members return a {@code film} record, so a batched
 * child {@code @service} keyed by {@code Set<FilmRecord>} under it has no one member to take its keys
 * through. Only the store's capture reads it; nothing is emitted from it.
 */
public record FilmPair(FilmRecord first, FilmRecord second) {}
