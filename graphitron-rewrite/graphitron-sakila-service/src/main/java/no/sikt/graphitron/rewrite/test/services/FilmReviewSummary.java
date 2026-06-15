package no.sikt.graphitron.rewrite.test.services;

/**
 * R200 fixture: consumer-authored input bean whose record components ({@code film}, {@code stars})
 * diverge from the SDL input field names ({@code filmId}, {@code rating}). The SDL
 * {@code FilmReviewSummaryInput} bridges the divergence with {@code @field(name: "film")} /
 * {@code @field(name: "stars")}.
 *
 * <p>The fetcher generator must read {@code env.getArgument} by the SDL field name (the wire/Map key)
 * and bind positionally to this record's canonical constructor by component name. If {@code @field}
 * were ignored, classification would reject the bean (no component matches {@code filmId} /
 * {@code rating}); if the binding mis-positioned, the round-trip would compute the wrong
 * {@code reviewId}. The compile of the emitted {@code createFilmReviewSummary} helper is itself the
 * belt-and-suspenders net for the canonical-constructor arity invariant.
 */
public record FilmReviewSummary(
    Integer film,
    Integer stars
) {}
