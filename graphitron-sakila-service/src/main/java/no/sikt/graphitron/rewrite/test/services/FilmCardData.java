package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Execution-tier fixture: a custom Java record that wraps a single typed
 * {@link FilmRecord}. Used as the backing class for a {@code @record(record: {...})}
 * GraphQL type whose child field {@code film: Film} traverses through the canonical
 * {@code film()} accessor — the classifier auto-derives a
 * {@code BatchKey.AccessorKeyedSingle} BatchKey from
 * the typed accessor return ({@code FilmRecord} → {@code film} table) and the framework
 * batch-fetches the full Film row by PK at request time.
 *
 * <p>Populated via {@code InventoryExtensions.filmCardData}'s {@code Field.convert(...)}
 * shape: only the PK ({@code film_id}) is set on the embedded {@link FilmRecord}; the
 * remaining columns are placeholders the framework re-fetches when the child Film is
 * selected.
 */
public record FilmCardData(FilmRecord film) {

    /**
     * Execution-tier hook: surfaces a {@link RecordExampleType} so the {@code example} field
     * on the SDL {@code FilmCardWrapper} type exercises three accessor-resolution arms in one
     * fixture. Same instance per call — the fixture covers the resolution + emit path, not
     * value identity.
     */
    public RecordExampleType example() {
        return new RecordExampleType("alpha");
    }
}
