package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.jooq.UpdatableRecord;

/** A {@link TenantServiceStub} bean whose record member names one node type, beside one naming a union. */
public record TenantFilmAndThingBean(FilmRecord film, UpdatableRecord<?> thing) {}
