package no.fellesstudentsystem.graphitron.codereferences.services;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Fake service for record tests.
 */
public class JOOQRecordInputFetchService {
    public JOOQRecordInputFetchService(DSLContext context) {}

    public CustomerRecord customer(String id, CustomerRecord record) {
        return null;
    }

    public List<CustomerRecord> customerListed(List<CustomerRecord> record) {
        return null;
    }
}
