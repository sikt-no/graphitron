package no.sikt.graphitron.mcp.fixtures.code;

/**
 * A record for the {@code code} tool's cases, and the fixture that makes one of its arms real rather
 * than contrived: a record's classfile carries its accessors and its mandated {@code equals},
 * {@code hashCode} and {@code toString}, and this source declares none of them. The two populations
 * are documented as allowed to disagree, and a record is where they always do.
 */
public record FilmCard(Integer filmId, String title) {}
