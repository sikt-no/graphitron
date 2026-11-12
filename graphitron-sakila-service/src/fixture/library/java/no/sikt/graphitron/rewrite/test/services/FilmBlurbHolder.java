package no.sikt.graphitron.rewrite.test.services;

/**
 * The library's holder, declared under the service corpus's own name and with other contents, so a
 * graph reading the corpus and the library meets one class name on two entries.
 */
public record FilmBlurbHolder(lib.Blurb blurb) {}
