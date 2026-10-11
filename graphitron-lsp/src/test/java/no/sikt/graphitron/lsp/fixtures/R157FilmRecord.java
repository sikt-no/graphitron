package no.sikt.graphitron.lsp.fixtures;

/**
 * Pipeline test fixture: a Java {@code record} so the classifier
 * classifies it as a Java record type
 * and the classpath capture reads its components. Component names match the
 * SDL field names in the classifier pipeline test that binds this class.
 */
public record R157FilmRecord(Integer filmId, String title) {}
