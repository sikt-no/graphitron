package no.sikt.graphitron.rewrite.test.services;

/**
 * The reading half of the shared-value-type fixture: a class-backed parent holding
 * {@link SharedTranslations} as a record component, so the component read is the second producer
 * of that type.
 */
public record TranslationSummary(SharedTranslations translations, String note) {}
