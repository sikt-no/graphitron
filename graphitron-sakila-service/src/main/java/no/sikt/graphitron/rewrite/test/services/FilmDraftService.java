package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture behind {@code Mutation.countFilmDrafts}: a {@code @service} mutation taking a list of
 * input objects bound to {@link FilmDraft}, whose payload's error channel carries a
 * {@code {handler: VALIDATION}} member. That handler makes the wrapper validate each
 * {@code FilmDraft} this method is about to receive, against the constraints declared on it, and
 * answer with one error per violation instead of calling this method. With every draft valid the
 * method runs and counts them; {@code count} is only ever set here, so a null {@code count} beside
 * the errors shows the method was not called.
 */
public final class FilmDraftService {

    private FilmDraftService() {}

    public static FilmDraftsCountedPayload count(List<FilmDraft> drafts) {
        return new FilmDraftsCountedPayload(drafts.size(), List.of());
    }
}
