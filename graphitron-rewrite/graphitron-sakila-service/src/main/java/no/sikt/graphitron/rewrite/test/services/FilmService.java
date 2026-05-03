package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import org.jooq.DSLContext;
import org.jooq.Record1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R49 ServiceRecordField Phase A fixture — child {@code @service} method with a non-table
 * scalar return type.
 *
 * <p>The signature uses {@code Record1<Integer>} (mapped) keys rather than
 * {@code Row1<Integer>} so the body can call {@link Record1#value1()} to extract each
 * key's column value. {@code Row1} is jOOQ's structural row-of-fields type and exposes no
 * value accessor; {@code Record1} extends {@code Row1} and adds value accessors. This
 * classifies the field's {@code BatchKey} as {@link no.sikt.graphitron.rewrite.model.BatchKey.MappedRecordKeyed},
 * so the framework's emitted lambda passes a {@code Set<Record1<Integer>>} into this method.
 *
 * <p>Phase B (R32) generated rows-method calls this; Phase A's emitter shipped a stub body
 * that threw at request time.
 */
public final class FilmService {

    private FilmService() {}

    /**
     * Looks up the {@code title} column for each requested film id and returns a map keyed by
     * the {@code Record1<Integer>} the framework supplies, with values uppercased.
     *
     * <p>The lookup pulls each title from the {@code film} table and returns
     * {@code title.toUpperCase()}, demonstrating that {@code @service} child fields with
     * scalar returns work end-to-end against the live fixtures database.
     */
    public static Map<Record1<Integer>, String> titleUppercase(Set<Record1<Integer>> filmIds, DSLContext dsl) {
        List<Integer> ids = filmIds.stream().map(Record1::value1).toList();
        Map<Integer, String> titlesById = dsl
            .select(Film.FILM.FILM_ID, Film.FILM.TITLE)
            .from(Film.FILM)
            .where(Film.FILM.FILM_ID.in(ids))
            .fetchMap(Film.FILM.FILM_ID, Film.FILM.TITLE);

        Map<Record1<Integer>, String> result = new LinkedHashMap<>();
        for (Record1<Integer> key : filmIds) {
            String title = titlesById.get(key.value1());
            result.put(key, title != null ? title.toUpperCase() : null);
        }
        return result;
    }
}
