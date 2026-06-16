package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import no.sikt.graphitron.rewrite.test.jooq.tables.Language;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;
import org.jooq.DSLContext;
import org.jooq.Record1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * R311 compilation / execution-tier fixtures: a jOOQ {@code TableRecord} bound <em>directly</em> as a
 * {@code @service} input param (root singular, root {@code List<…>}, composite-key, and the
 * child-{@code @service} coordinate). The generated fetcher constructs the record via a
 * {@code createFilmRecord} / {@code createFilmRecordList} helper ({@code NodeIdEncoder.decodeValues}
 * for the {@code @nodeId} identity + {@code record.fromArray(…, Tables.FILM.<col>)} for the
 * {@code @field} columns) rather than casting the wire {@code Map}; each body reads the populated
 * columns back to prove the decode-and-materialise seam end-to-end against PostgreSQL. Exercises the
 * non-deprecated {@code fromArray} coercion against the real jOOQ catalog at compile time.
 */
public final class FilmRecordService {

    private FilmRecordService() {}

    /**
     * R311 root singular: the {@code @service} param is a jOOQ {@link FilmRecord} constructed from a
     * {@code @nodeId} identity (decoded {@code film_id}) plus {@code @field} columns ({@code title},
     * {@code release_year}). Reads all three back to prove the column-axis SET and the scalar-key
     * decode land together.
     */
    public static String modifyFilmRecord(FilmRecord in) {
        if (in == null) {
            return "none";
        }
        return "film:" + in.getFilmId() + ":title=" + in.getTitle() + ":year=" + in.getReleaseYear();
    }

    /**
     * R311 root list: the consumer's motivating shape ({@code List<…Record>} against {@code [Input!]!}).
     * One {@link FilmRecord} is constructed per element through the same singular helper the
     * {@code createFilmRecordList} variant delegates to; the body reads each decoded {@code film_id}
     * and set {@code title} back.
     */
    public static String modifyFilmRecords(List<FilmRecord> in) {
        if (in == null) {
            return "none";
        }
        return "films:" + in.stream()
            .map(f -> f.getFilmId() + "@" + f.getTitle())
            .collect(Collectors.joining(","));
    }

    /**
     * R311 composite-key root: a jOOQ {@link FilmActorRecord} (composite PK {@code actor_id, film_id})
     * whose {@code @nodeId} identity decodes both key columns in one {@code fromArray} call. Reads
     * both back.
     */
    public static String modifyFilmActorRecord(FilmActorRecord in) {
        if (in == null) {
            return "none";
        }
        return "filmActor:" + in.getActorId() + ":" + in.getFilmId();
    }

    /**
     * R311 child coordinate: a child {@code @service} rows-method on the {@code @table}-bound
     * {@code Film} parent, taking the parent keys (Sources, {@code Set<Record1<Integer>>}) plus a
     * {@link FilmRecord} arg. The arg classifies to a jOOQ-record call-site binding exactly as the root
     * param does, and the child rows-method emits {@code createFilmRecord} through {@code ArgCallEmitter}
     * (the real arm) — the binding is coordinate-agnostic. The body looks up each film's language and
     * keys it by the supplied parent {@code film_id}; the constructed {@code in} record's {@code title}
     * is unused here (the fixture's purpose is to compile the child call site against the real catalog).
     */
    public static Map<Record1<Integer>, LanguageRecord> modifiedLanguage(
            Set<Record1<Integer>> filmIds, FilmRecord in, DSLContext dsl) {
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
}
