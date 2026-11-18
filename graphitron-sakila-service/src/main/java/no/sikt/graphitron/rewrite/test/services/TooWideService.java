package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.nodeidfixture.tables.records.TooWideRecord;

import java.util.List;

/**
 * Pipeline-tier fixture: a {@code @service} handing back records of {@code nodeidfixture.too_wide},
 * whose 23-column primary key is wider than the typed record its records are lifted through.
 */
public final class TooWideService {

    private TooWideService() {}

    public static List<TooWideRecord> all() {
        return List.of();
    }

    public static TooWideRecord one() {
        return null;
    }
}
