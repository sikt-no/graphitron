package no.sikt.graphitron.rewrite.test.services;

import jakarta.validation.constraints.NotBlank;

/**
 * Fixture: one element of {@code Mutation.countFilmDrafts}'s list argument, bound from
 * {@code FilmDraftInput}. The constraint is what the field's VALIDATION handler checks before
 * {@link FilmDraftService#count} runs. See {@link FilmDraftService}.
 */
public record FilmDraft(@NotBlank String title) {
}
