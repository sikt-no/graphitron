package no.sikt.graphitron.lsp.fixtures;

/**
 * A record whose component is itself one of the backing fixtures, so an SDL type reached by reading
 * {@code detail} is reached by an accessor hop rather than grounded by a producer of its own. A
 * fixture with a class-typed member is what lets a test put a hop and a grounding on one type and
 * see which of the two the store's reader believes.
 */
public record FilmCardRecord(Integer filmId, R157FilmPojo detail) {}
