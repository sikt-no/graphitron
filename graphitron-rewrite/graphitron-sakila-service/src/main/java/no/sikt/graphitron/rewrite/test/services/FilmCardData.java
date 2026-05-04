package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * R61 execution-tier fixture: a custom Java record that wraps a {@code List<FilmRecord>}.
 * Used as the backing class for a {@code @record(record: {...})} GraphQL type whose child
 * field {@code films: [Film!]!} traverses through the canonical {@code films()} accessor —
 * the classifier auto-derives a
 * {@link no.sikt.graphitron.rewrite.model.BatchKey.AccessorKeyedMany} BatchKey from the typed
 * accessor return ({@code List<FilmRecord>} → {@code film} table) and the framework batches
 * dispatch via {@code loader.loadMany(keys, env)} keyed on the element table's PK
 * ({@code Record1<Integer>}), returning one Film row per key.
 *
 * <p>Populated via {@code InventoryExtensions.filmCardData}'s {@code Field.convert(...)}
 * shape: only the PK ({@code film_id}) is set on each embedded {@link FilmRecord}; remaining
 * columns are placeholders the framework re-fetches when the child Film is selected.
 *
 * <p>List cardinality (rather than single) sidesteps the validator's Invariant #10
 * single-cardinality {@code RecordTableField} rejection while still exercising the
 * accessor-derived lift end-to-end.
 */
public record FilmCardData(List<FilmRecord> films) {}
