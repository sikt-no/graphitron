package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.records.AddressRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.service.records.MockUpdateAddressAndCustomerResultRecord;
import org.jooq.DSLContext;

public class MockService {

    public MockService(DSLContext context) {
    }

    public MockUpdateAddressAndCustomerResultRecord mockUpdateAddressAndCustomer() {
        var result = new MockUpdateAddressAndCustomerResultRecord();

        var address1 = new AddressRecord();
        address1.setAddressId(9);
        result.setMyAddress(address1);

        var address2 = new AddressRecord();
        address2.setAddressId(14);
        result.setAddress(address2);

        var customer = new CustomerRecord();
        customer.setCustomerId(6);
        result.setCustomer(customer);

        return result;
    }
}
