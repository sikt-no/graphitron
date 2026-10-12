package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

/** A plain tenant scalar beside a member decoded from {@code @nodeId}: two tenant carriers. */
public record RateByFilmIdAndFilmBean(Integer filmId, FilmRecord film) {}
