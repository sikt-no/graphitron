package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

/**
 * A non-table wrapper a {@code @globalData} {@link TenantServiceStub} service returns: a scalar
 * the service computes beside a global row the payload re-projects on its own coordinate.
 */
public record GlobalLanguagePayload(String servedBy, LanguageRecord language) {}
