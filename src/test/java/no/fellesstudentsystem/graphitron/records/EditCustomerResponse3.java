package no.fellesstudentsystem.graphitron.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

import java.util.List;

public class EditCustomerResponse3 {
    public String getId3() {
        return "";
    }

    public CustomerRecord getCustomer3() {
        return new CustomerRecord();
    }

    public List<EditCustomerResponse4> getEdit4() {
        return List.of(new EditCustomerResponse4());
    }
}
