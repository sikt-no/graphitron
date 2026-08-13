package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution-tier fixture pair for the author-declared batch key on a class-backed parent: a producer
 * that hands back {@link FilmKeySummary} rows, and the batched child {@code @service} those rows host.
 *
 * <p>{@link #keyCensus} answers with facts about the batch it was handed rather than with data, which
 * is what lets one query pin all three contract claims at once and without static counters:
 *
 * <ul>
 *   <li><b>One dispatch.</b> The reported size is the whole batch's, so a per-parent call would report
 *       1 for every row.</li>
 *   <li><b>Deduplication.</b> {@link #summaries} returns three rows over two distinct films, so the
 *       reported size is 2 and not 3. The keys the framework builds are fresh records carrying the key
 *       columns only, which is exactly what makes two rows pointing at the same film compare equal and
 *       collapse in the loader.</li>
 *   <li><b>Sparse keys.</b> The title read off the key is {@code null}: the key carries the key columns
 *       and nothing else, even though the accessor it was read from returned a fully populated record.
 *       A pass-through of the held record would report a real title here.</li>
 * </ul>
 */
public final class FilmKeySummaryService {

    private FilmKeySummaryService() {}

    /**
     * Three summaries over two distinct films, so the child's batch has one duplicate to collapse.
     * The records are fully populated (a real {@code selectFrom}), which is what makes the sparse-key
     * claim below a claim about the framework's key building rather than about the producer's data.
     */
    public static List<FilmKeySummary> summaries(DSLContext dsl) {
        var films = dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(1, 2))
            .orderBy(Tables.FILM.FILM_ID)
            .fetch();
        if (films.size() < 2) return List.of();
        FilmRecord first = films.get(0);
        FilmRecord second = films.get(1);
        return List.of(
            new FilmKeySummary(first, "first"),
            new FilmKeySummary(second, "second"),
            new FilmKeySummary(first, "first-again"));
    }

    /** The batched child: reports the batch size it was handed and the title it can read off a key. */
    public static Map<FilmRecord, String> keyCensus(Set<FilmRecord> keys, DSLContext dsl) {
        Map<FilmRecord, String> out = new LinkedHashMap<>();
        for (FilmRecord key : keys) {
            out.put(key, keys.size() + "|" + key.getTitle());
        }
        return out;
    }
}
