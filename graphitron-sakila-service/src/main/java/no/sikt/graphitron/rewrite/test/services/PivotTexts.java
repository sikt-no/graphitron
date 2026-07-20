package no.sikt.graphitron.rewrite.test.services;

/**
 * Class-backed carrier for the {@code @pivot} coexistence fixture: the plain
 * {@code TranslatedTexts} projection type, reached in the sakila-example schema through a
 * {@code @pivot} field (a graphitron-built jOOQ record), as an ordinary nested object on the
 * {@code pivot_nesting_host} table, and as this {@code @service}-produced Java record. The
 * per-slot coordinates therefore carry the two-shape reach union and are served by the run-time
 * source-shape dispatch.
 */
public record PivotTexts(String nn, String nb, String se, String en) {}
