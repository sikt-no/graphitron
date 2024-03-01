package no.fellesstudentsystem.graphitron.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

import java.util.List;

public class EditCustomerResponse2 {
    public String getId2() {
        return "";
    }

    public CustomerRecord getCustomer() {
        return new CustomerRecord();
    }

    public List<CustomerRecord> getCustomerList() {
        return List.of();
    }
}
