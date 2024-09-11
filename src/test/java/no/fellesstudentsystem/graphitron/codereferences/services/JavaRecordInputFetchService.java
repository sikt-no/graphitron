package no.fellesstudentsystem.graphitron.codereferences.services;

import no.fellesstudentsystem.graphitron.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Fake service for mapper tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class JavaRecordInputFetchService {
    public JavaRecordInputFetchService(DSLContext context) {}

    public CustomerRecord customer(String id, DummyRecord record) {
        return null;
    }

    public List<CustomerRecord> customerListed(List<DummyRecord> record) {
        return null;
    }
}
