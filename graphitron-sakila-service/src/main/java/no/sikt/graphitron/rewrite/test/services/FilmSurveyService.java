package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/** Takes a {@link FilmSurvey} built by its setters, and counts it. */
public final class FilmSurveyService {

    private FilmSurveyService() {}

    public static FilmDraftsCountedPayload count(FilmSurvey survey) {
        return new FilmDraftsCountedPayload(survey == null ? 0 : 1, List.of());
    }
}
