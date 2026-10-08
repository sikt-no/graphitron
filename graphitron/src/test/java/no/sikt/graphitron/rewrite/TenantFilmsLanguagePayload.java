package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

import java.util.List;

/**
 * A non-table wrapper a root {@link TenantServiceStub} service returns, holding the tenant's rows
 * beside a global row the payload re-projects on its own coordinate.
 */
public record TenantFilmsLanguagePayload(List<FilmRecord> films, LanguageRecord language) {}
