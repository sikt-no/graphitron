package no.fellesstudentsystem.graphitron.services;

import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.AddressRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Fake service for mutation tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class TestFetchCustomerService {
    private final DSLContext context;

    public TestFetchCustomerService(DSLContext context) {
        this.context = context;
    }

    public List<CustomerRecord> customersQuery(List<String> ids) {
        return null;
    }

    public List<TestCustomerRecord> customersQueryRecord(List<String> ids) {
        return null;
    }

    public List<CustomerRecord> customersQuery0(List<String> ids, int pageSize, String after) {
        return null;
    }

    public int countCustomersQuery0(List<String> ids) {
        return 0;
    }

    public CustomerRecord customersQuery1(String id) {
        return null;
    }

    public List<CustomerRecord> customersQuery2() {
        return null;
    }

    public TestCustomerRecord customerQueryWithRecord() {
        return null;
    }

    public List<AddressRecord> historicalAddresses(List<String> ids, int pageSize, String after) {
        return null;
    }

    public int countHistoricalAddresses(List<String> ids) {
        return 0;
    }

    public List<AddressRecord> historicalAddresses(List<String> ids) {
        return null;
    }
}
