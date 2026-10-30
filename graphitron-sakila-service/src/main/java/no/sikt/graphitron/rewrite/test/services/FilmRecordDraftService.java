package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

import java.util.List;

/**
 * Fixture behind {@code Mutation.countFilmRecordDrafts} and {@code Mutation.countFilmRecordDraft}:
 * {@code @service} mutations taking jOOQ {@link FilmRecord}s, whose payload's error channel carries a
 * {@code {handler: VALIDATION}} member. That handler makes the wrapper check each column a record
 * writes against the constraints on the record's property for it, and answer with one error per
 * violation instead of calling these methods. {@code count} is only ever set here, so a null
 * {@code count} beside the errors shows the method was not called.
 */
public final class FilmRecordDraftService {

    private FilmRecordDraftService() {}

    public static FilmDraftsCountedPayload count(List<FilmRecord> drafts) {
        return new FilmDraftsCountedPayload(drafts.size(), List.of());
    }

    /** Counts the draft and the record as one each. */
    public static FilmDraftsCountedPayload countOne(FilmDraft draft, FilmRecord film) {
        return new FilmDraftsCountedPayload(2, List.of());
    }
}
