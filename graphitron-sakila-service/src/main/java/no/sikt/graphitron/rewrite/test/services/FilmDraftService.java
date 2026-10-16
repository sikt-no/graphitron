package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture behind {@code Mutation.countFilmDrafts}: a {@code @service} mutation taking a list of
 * input objects, whose payload's error channel carries a {@code {handler: VALIDATION}} member. That
 * handler makes the wrapper validate the argument before this method runs, one element at a time.
 * The generated input class the pass validates carries no constraint annotations, so the pass finds
 * no violations and the body always runs: what the fixture pins is that a list argument reaches
 * here at all, where it once failed every call by being read as a single input object.
 */
public final class FilmDraftService {

    private FilmDraftService() {}

    public static FilmDraftsCountedPayload count(List<FilmDraft> drafts) {
        return new FilmDraftsCountedPayload(drafts.size(), List.of());
    }
}
