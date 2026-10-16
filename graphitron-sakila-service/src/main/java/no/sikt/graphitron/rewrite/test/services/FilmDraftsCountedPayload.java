package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: the payload of {@code Mutation.countFilmDrafts}, the drafts' count plus the errors slot
 * as {@code List<?>}. See {@link FilmDraftService}.
 */
public record FilmDraftsCountedPayload(Integer count, List<?> errors) {
}
