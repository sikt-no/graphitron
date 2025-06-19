package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.service.records.HelloWorldInput;
import org.jooq.DSLContext;

public class CustomerService {
    public CustomerService(DSLContext context) {
    }

    public CustomerRecord customer() {
        return null;
    }

    public CustomerRecord customer(CustomerRecord input) {
        var customer = new CustomerRecord();
        customer.setCustomerId(input.getCustomerId());
        return customer;
    }

    public CustomerRecord customer(HelloWorldInput input) {
        // We don't have access to nodeIdStrategy here to decode and use customerId
        var customer = new CustomerRecord();
        customer.setFirstName(input.getName());
        return customer;
    }
}
