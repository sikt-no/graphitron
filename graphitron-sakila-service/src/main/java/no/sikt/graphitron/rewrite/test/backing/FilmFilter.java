package no.sikt.graphitron.rewrite.test.backing;

/** A producer's parameter, backing the input object of the argument feeding it. */
public record FilmFilter(String title, NestedFilter nested) {}
