package no.sikt.graphitron.rewrite.test.services;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Fixture: the argument of {@code Mutation.countFilmBundleDraft}, bound from
 * {@code FilmBundleDraftInput}. Its members reach the SDL three different ways, which is what the
 * field's validation errors have to name: {@code capacity} flattens out of the grouping input
 * {@code limits}, so its SDL path has two fields where its Java path has one; {@code films} binds
 * a list of {@link FilmDraft}, validated element by element through {@code @Valid}; and the
 * class-level {@link FilmBundleFits} names no member at all. See {@link FilmBundleDraftService}.
 */
@FilmBundleFits
public record FilmBundleDraft(String name, @Positive Integer capacity, List<@Valid FilmDraft> films) {
}
