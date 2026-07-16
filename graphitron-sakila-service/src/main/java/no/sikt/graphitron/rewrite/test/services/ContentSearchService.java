package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.ContentRecord;

import java.util.List;

/**
 * Execution-tier fixture: a {@code @service} returning a single-table discriminated interface
 * ({@code Content}: the shared {@code content} table discriminated by {@code CONTENT_TYPE} into
 * {@code FilmContent} / {@code ShortContent}).
 *
 * <p>Every returned record is the <em>same</em> {@link ContentRecord} regardless of subtype, so the
 * runtime Java class carries no discriminator (contrast {@link PolymorphicSearchService}, whose
 * distinct {@code FilmRecord} / {@code ActorRecord} classes are the discriminator). The generated
 * fetcher therefore collects each record's primary key, re-fetches by PK, and routes each row off the
 * live {@code CONTENT_TYPE} value via the {@code Content} {@code TypeResolver}. The methods set only
 * the primary key and leave the rest for Graphitron to fetch.
 */
public final class ContentSearchService {

    private ContentSearchService() {}

    /**
     * Single-cardinality return: one PK-only {@link ContentRecord} pointing at content row 1, a
     * {@code FILM} row, so the live discriminator routes it to {@code FilmContent} (a route (a)-style
     * class dispatch could not tell the subtypes apart, since the record class is always
     * {@code ContentRecord}).
     */
    public static ContentRecord searchOne() {
        ContentRecord content = new ContentRecord();
        content.setContentId(1);
        return content;
    }

    /**
     * List-cardinality return exercising both subtypes so a misroute is observable, plus a PK with no
     * live row so the drop contract is observable: content row 1 ({@code FILM}), content row 3
     * ({@code SHORT}), and content row 999 (absent, dropped from the payload).
     */
    public static List<ContentRecord> searchContents() {
        ContentRecord film = new ContentRecord();
        film.setContentId(1);
        ContentRecord shortContent = new ContentRecord();
        shortContent.setContentId(3);
        ContentRecord missing = new ContentRecord();
        missing.setContentId(999);
        return List.of(film, shortContent, missing);
    }
}
