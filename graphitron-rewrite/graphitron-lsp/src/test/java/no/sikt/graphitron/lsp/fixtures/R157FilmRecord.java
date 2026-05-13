package no.sikt.graphitron.lsp.fixtures;

/**
 * R157 pipeline test fixture: a Java {@code record} so the classifier
 * produces {@link no.sikt.graphitron.rewrite.model.GraphitronType.JavaRecordType}
 * and {@link no.sikt.graphitron.rewrite.catalog.ClasspathScanner} reads the
 * Record attribute into {@code recordComponents}. Component names match the
 * SDL field names in {@code R157Pipeline.recordBackedTypeSurfacesComponentsThroughSnapshot}.
 */
public record R157FilmRecord(Integer filmId, String title) {}
