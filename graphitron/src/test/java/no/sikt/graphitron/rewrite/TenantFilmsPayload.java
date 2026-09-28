package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/** A non-table wrapper a root {@link TenantServiceStub} service returns, holding the tenant's rows. */
public record TenantFilmsPayload(List<FilmRecord> films) {}
