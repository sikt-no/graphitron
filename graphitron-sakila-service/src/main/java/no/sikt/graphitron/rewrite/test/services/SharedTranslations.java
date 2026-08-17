package no.sikt.graphitron.rewrite.test.services;

/**
 * The shared class-backed value type of the compilation-tier fixture: produced both by the
 * batched child {@code @service} {@link SharedValueTypeService#translationsByFilm} on the
 * {@code @table} parent {@code Film} and by the {@link TranslationSummary#translations()} record
 * component read. Both producers put this same object at {@code env.getSource()}, so the SDL
 * type's child fetchers are generated once and serve both.
 */
public record SharedTranslations(String nb, String en) {}
