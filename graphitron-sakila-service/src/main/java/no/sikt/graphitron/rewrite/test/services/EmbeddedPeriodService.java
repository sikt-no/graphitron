package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.EmbeddedPeriodRecord;

import java.util.List;

/**
 * Fixture: a service taking embedded_period's generated record, built from an input object, so an
 * input nested in it groups onto the record's columns. Read by fact documents only.
 */
public final class EmbeddedPeriodService {

    private EmbeddedPeriodService() {}

    public static String count(List<EmbeddedPeriodRecord> periods) {
        return String.valueOf(periods.size());
    }
}
