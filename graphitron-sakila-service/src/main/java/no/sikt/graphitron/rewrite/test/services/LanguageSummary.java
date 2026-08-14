package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture: a scalar-only class-backed parent for the {@code @sourceRow}-declared batch key on a
 * child {@code @service}. The record carries the key column as a bare {@code int}, so neither
 * producer inference can serve it: it is not a {@code LanguageRecord}, and it exposes no accessor
 * returning one. {@link LanguageSummaryLifter#key} is the declared third route.
 */
public record LanguageSummary(int languageId, String label) {}
