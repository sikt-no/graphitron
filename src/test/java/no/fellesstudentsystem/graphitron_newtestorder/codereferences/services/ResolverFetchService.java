package no.fellesstudentsystem.graphitron_newtestorder.codereferences.services;

import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

/**
 * Fake service for resolver tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class ResolverFetchService {
    public ResolverFetchService(DSLContext context) {}

    public CustomerRecord query(DummyRecord record) {
        return null;
    }

    public CustomerRecord query(CustomerRecord record) {
        return null;
    }
}
