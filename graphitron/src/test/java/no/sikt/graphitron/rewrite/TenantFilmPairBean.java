package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/** A {@link TenantServiceStub} bean pairing one film with a second, optional one. */
public record TenantFilmPairBean(FilmRecord film, FilmRecord alsoFilm) {}
