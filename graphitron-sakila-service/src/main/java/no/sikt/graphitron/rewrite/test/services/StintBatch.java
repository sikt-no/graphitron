package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.StintRecord;

import java.util.List;

/**
 * Fixture for a key row on a table with no primary key: {@code stints()} hands over {@code stint}
 * records, and {@code stint} has only a unique constraint, so a field fetching them under this class
 * has nothing to batch by. Only the store's capture reads it; nothing is emitted from it.
 */
public record StintBatch(List<StintRecord> stints) {}
