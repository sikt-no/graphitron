package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import no.sikt.graphitron.rewrite.test.jooq.tables.Language;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Row1;
import org.jooq.impl.DSL;

import java.util.ArrayList;
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

    /**
     * R61 sibling fixture exercising the {@code Set<Row1<Integer>>} source-shape arm of the
     * @service classifier. {@link Row1} has no {@code value<N>()} accessor (that's
     * {@link Record1}'s addition), so the developer composes against the column tuple via
     * {@code DSL.row(...).in(filmIds)} at SQL time, then reconstructs each key by wrapping the
     * fetched scalar in a fresh {@code DSL.row(value)} — value-based {@code equals}/
     * {@code hashCode} on {@link Row1} make the result {@code Map}'s keys round-trip cleanly to
     * the input keys.
     *
     * <p>Confirms the framework's {@code field<N>()}-based dispatch keeps working under the
     * Row1-source path: values flow through the SQL VALUES table (the framework-side bind) and
     * the developer-side response Map keyed on the same Row1 instances.
     */
    public static Map<Row1<Integer>, String> titleLowercase(Set<Row1<Integer>> filmIds, DSLContext dsl) {
        if (filmIds.isEmpty()) return new LinkedHashMap<>();

        @SuppressWarnings({"unchecked", "rawtypes"})
        Row1<Integer>[] keysArray = filmIds.toArray(new Row1[0]);

        Map<Integer, String> titlesById = dsl
            .select(Film.FILM.FILM_ID, Film.FILM.TITLE)
            .from(Film.FILM)
            .where(DSL.row(Film.FILM.FILM_ID).in(keysArray))
            .fetchMap(Film.FILM.FILM_ID, Film.FILM.TITLE);

        Map<Row1<Integer>, String> result = new LinkedHashMap<>();
        for (var entry : titlesById.entrySet()) {
            result.put(DSL.row(entry.getKey()), entry.getValue().toLowerCase());
        }
        return result;
    }

    /**
     * R70 sibling fixture exercising the typed-{@link FilmRecord} source-shape arm. The
     * developer signs {@code Set<FilmRecord>} on the parameter and {@code Map<FilmRecord, String>}
     * on the return, which classifies as {@link no.sikt.graphitron.rewrite.model.BatchKey.MappedTableRecordKeyed}
     * (the variant carries the typed record class). The framework's emitted lambda extracts the
     * parent's {@code FilmRecord} via {@code env.getSource().into(Tables.FILM)} and calls this
     * method directly with the typed-record set.
     *
     * <p>Reads each {@code FilmRecord}'s {@code title} via {@link FilmRecord#getTitle()} and
     * returns the title-case rendition. No SQL fetch is required because the typed record
     * already carries every column on the parent table.
     */
    /**
     * R177 fixture: child {@code @service} with a {@link no.sikt.graphitron.rewrite.model.ReturnTypeRef.TableBoundReturnType}
     * return ({@code Language}). Post-R177 the rows-method's {@code V} is
     * {@code tb.table().recordClass()} (here {@link LanguageRecord}), so the structurally-
     * required signature is {@code Map<Record1<Integer>, LanguageRecord>}. Pre-R177 {@code V}
     * was raw {@code org.jooq.Record} and this signature would have been rejected by the
     * validator's strict {@code TypeName.equals}; the fixture compiles (and classifies)
     * only post-R177, making {@code mvn compile -pl :graphitron-sakila-example} the load-
     * bearing guarantee against future re-widening of the emit site.
     *
     * <p>Body looks up each film's language by joining {@code film} to {@code language} on the
     * {@code film_language_id_fkey} FK and returns the per-film {@link LanguageRecord}.
     */
    public static Map<Record1<Integer>, LanguageRecord> languageByService(Set<Record1<Integer>> filmIds, DSLContext dsl) {
        List<Integer> ids = filmIds.stream().map(Record1::value1).toList();
        Map<Integer, Integer> langIdByFilmId = dsl
            .select(Film.FILM.FILM_ID, Film.FILM.LANGUAGE_ID)
            .from(Film.FILM)
            .where(Film.FILM.FILM_ID.in(ids))
            .fetchMap(Film.FILM.FILM_ID, Film.FILM.LANGUAGE_ID);
        Map<Integer, LanguageRecord> recordByLangId = dsl
            .selectFrom(Language.LANGUAGE)
            .where(Language.LANGUAGE.LANGUAGE_ID.in(langIdByFilmId.values()))
            .fetchMap(Language.LANGUAGE.LANGUAGE_ID);

        Map<Record1<Integer>, LanguageRecord> result = new LinkedHashMap<>();
        for (Record1<Integer> key : filmIds) {
            Integer langId = langIdByFilmId.get(key.value1());
            result.put(key, langId == null ? null : recordByLangId.get(langId));
        }
        return result;
    }

    public static Map<FilmRecord, String> titleTitlecase(Set<FilmRecord> films) {
        Map<FilmRecord, String> result = new LinkedHashMap<>();
        for (FilmRecord film : films) {
            String title = film.getTitle();
            result.put(film, title == null ? null : toTitleCase(title));
        }
        return result;
    }

    private static String toTitleCase(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean nextUpper = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                out.append(c);
                nextUpper = true;
            } else if (nextUpper) {
                out.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /**
     * R285 lift-back fixture: a list-cardinality child {@code @service} whose mapped batch returns
     * the {@code film_actor} junction rows per film. The element type {@code FilmActor} carries an
     * {@code actor: Actor @reference} sub-field, which is a correlated multiset, not a stored
     * column. Pre-R285 the rows-method returned these {@code FilmActorRecord}s verbatim and the
     * {@code actor} fetcher threw {@code "Field \"actor\" is not contained in row type"}; the lift
     * re-projects each returned record's PK through {@code FilmActor.$fields(...)} so the multiset
     * column is present. This is the in-tree analogue of opptak's
     * {@code Sak.saksdokumenter -> Saksdokument.dokument}.
     *
     * <p>Mapped container (Set keys, Map return) + list cardinality. Returns only films present in
     * the batch; a film with no cast simply maps to an empty list.
     */
    public static Map<Record1<Integer>, List<FilmActorRecord>> castMembersByFilm(
        Set<Record1<Integer>> filmIds, DSLContext dsl
    ) {
        List<Integer> ids = filmIds.stream().map(Record1::value1).toList();
        Map<Integer, List<FilmActorRecord>> byFilmId = new LinkedHashMap<>();
        for (FilmActorRecord r : dsl.selectFrom(Tables.FILM_ACTOR)
                .where(Tables.FILM_ACTOR.FILM_ID.in(ids))
                .orderBy(Tables.FILM_ACTOR.ACTOR_ID)
                .fetch()) {
            byFilmId.computeIfAbsent(r.getFilmId(), k -> new ArrayList<>()).add(r);
        }
        Map<Record1<Integer>, List<FilmActorRecord>> result = new LinkedHashMap<>();
        for (Record1<Integer> key : filmIds) {
            result.put(key, byFilmId.getOrDefault(key.value1(), List.of()));
        }
        return result;
    }
}
