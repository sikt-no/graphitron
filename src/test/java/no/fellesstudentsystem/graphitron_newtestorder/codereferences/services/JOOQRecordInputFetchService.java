package no.fellesstudentsystem.graphitron_newtestorder.codereferences.services;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Fake service for record tests.
 */
public class JOOQRecordInputFetchService {
    private final DSLContext context;

    public JOOQRecordInputFetchService(DSLContext context) {
        this.context = context;
    }

    public CustomerRecord customers(String id, CustomerRecord record) {
        return null;
    }

    public List<CustomerRecord> customersListed(List<CustomerRecord> record) {
        return null;
    }
}
