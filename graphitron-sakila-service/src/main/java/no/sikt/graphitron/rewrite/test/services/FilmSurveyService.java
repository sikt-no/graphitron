package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Takes a {@link FilmSurvey} built by its setters, and counts it; and takes a {@link FilmPanel}, a
 * record bean nested here whose member is a list of another nested record, so the capture is shown
 * to build classes declared inside another class, and the ones those name in turn.
 */
public final class FilmSurveyService {

    private FilmSurveyService() {}

    /** A bean nested in its service, holding a list of another nested bean. */
    public record FilmPanel(String note, List<FilmPanelEntry> films) {}

    /** Named only by {@link FilmPanel}'s member, so it is reached through that bean alone. */
    public record FilmPanelEntry(String title) {}

    public static FilmDraftsCountedPayload count(FilmSurvey survey) {
        return new FilmDraftsCountedPayload(survey == null ? 0 : 1, List.of());
    }

    public static FilmDraftsCountedPayload countPanel(FilmPanel panel) {
        return new FilmDraftsCountedPayload(panel == null || panel.films() == null ? 0 : panel.films().size(),
            List.of());
    }
}
