package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Inventory;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.services.FilmCardData;
import org.jooq.Converter;
import org.jooq.Field;

import java.util.List;

/**
 * R61 execution-tier fixtures: {@code @externalField} methods returning
 * {@code Field<TableRecord<?>>} and {@code Field<CustomJavaRecord>} where the custom record
 * carries a typed {@code TableRecord} accessor.
 *
 * <p>Both methods use jOOQ's {@code Field.convert(Converter)} to lift a per-row scalar
 * (the parent inventory's {@code film_id}) into a typed {@code FilmRecord} (or a custom
 * record wrapping one). Only the PK is set on the {@code FilmRecord} — the framework
 * batch-fetches any non-PK columns on demand via the standard {@code @record}-parent paths
 * (R61 {@code RowKeyed} via FK; the {@code @record(JavaRecord)} variant additionally fires
 * the {@code AccessorKeyedSingle} lift via the typed {@code film()} accessor on
 * {@code FilmCardData}).
 */
public final class InventoryExtensions {

    private InventoryExtensions() {}

    /**
     * Per-inventory {@code Field<FilmRecord>} carrying only the PK ({@code film_id}). Wired
     * by {@code Inventory.filmRef} on the {@code @table}-typed Inventory parent.
     */
    public static Field<FilmRecord> filmRef(Inventory table) {
        return table.FILM_ID.convert(Converter.from(Integer.class, FilmRecord.class, filmId -> {
            FilmRecord f = new FilmRecord();
            f.setFilmId(filmId);
            return f;
        }));
    }

    /**
     * Per-inventory {@code Field<FilmCardData>} where {@link FilmCardData} is a Java record
     * carrying a typed {@code List<FilmRecord> films} component. The classifier picks the
     * canonical {@code films()} accessor on {@code FilmCardData} and produces an
     * {@link no.sikt.graphitron.rewrite.model.BatchKey.AccessorKeyedMany} BatchKey for the
     * GraphQL child field {@code films: [Film!]!}, lifting the embedded records back into
     * Graphitron scope so the framework can batch-fetch the full Film rows by PK at request
     * time. Each inventory contributes a singleton list (one film per inventory) by
     * construction; {@code loadMany} batches across all inventories.
     */
    public static Field<FilmCardData> filmCardData(Inventory table) {
        return table.FILM_ID.convert(Converter.from(Integer.class, FilmCardData.class, filmId -> {
            FilmRecord f = new FilmRecord();
            f.setFilmId(filmId);
            return new FilmCardData(List.of(f));
        }));
    }
}
