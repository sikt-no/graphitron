package no.sikt.graphitron.codereferences.services;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
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
