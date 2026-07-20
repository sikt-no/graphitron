package no.sikt.graphitron.rewrite.test.services;

/**
 * A plain value record reused across source shapes (the mixed-source reach). {@code FilmBlurb} is
 * embedded as a directiveless nesting projection of the {@code @table Film} parent (its
 * {@code description} field reads {@code film.description} off the parent jOOQ {@code Record}) and is
 * also carried by {@link FilmBlurbHolder}, produced by {@link FilmBlurbHolderService}, where the same
 * field reads the {@code description()} accessor. The generator serves both reaches from one datafetcher
 * per coordinate that dispatches on {@code source instanceof org.jooq.Record}.
 *
 * <p>Top-level (not nested) so its binary name has no {@code $} segment, matching how the reflection
 * binding compares the producer's declared return type by name.
 */
public record FilmBlurb(String description) {}
