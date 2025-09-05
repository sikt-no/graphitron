package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.service.records.HelloWorldInput;
import no.sikt.graphitron.example.service.records.UpdateCustomerEmailRecord;
import no.sikt.graphitron.example.service.records.UpdateCustomerEmailResult;
import org.jooq.DSLContext;

import java.util.List;

public class CustomerService {
    public CustomerService(DSLContext context) {
    }

    public CustomerRecord customer() {
        var customer = new CustomerRecord();
        customer.setCustomerId(3);
        return customer;
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

    public List<CustomerRecord> createCustomerEmail() {
        return List.of(
                createCustomer(4, "HARDCODED_FIRSTNAME_1", "HARDCODED_LASTNAME_1", "HARDCODED_EMAIL_1"),
                createCustomer(2, "HARDCODED_FIRSTNAME_2", "HARDCODED_LASTNAME_2", "HARDCODED_EMAIL_2"),
                createCustomer(3, "HARDCODED_FIRSTNAME_3", "HARDCODED_LASTNAME_3", "HARDCODED_EMAIL_3")
        );
    }

    private CustomerRecord createCustomer(int id, String firstName, String lastName, String email) {
        return new CustomerRecord(
                id, null, firstName, lastName, email, null, null, null, null, null
        );
    }

    public List<CustomerRecord> allCustomerEmails() {
        throw new IllegalStateException("You are not allowed to access emails");
    }

    public List<CustomerRecord> allCustomerEmails_IllegalArgument() {
        throw new IllegalArgumentException("This is the error message from the IllegalArgumentException");
    }

    public List<UpdateCustomerEmailResult> updateCustomerEmail(List<UpdateCustomerEmailRecord> input) {
        var customer = new CustomerRecord();
        UpdateCustomerEmailRecord first = input.get(0);
        customer.setCustomerId(first.getCustomerId());
        customer.setEmail(first.getEmail());
        return List.of(new UpdateCustomerEmailResult(customer));
    }

}
