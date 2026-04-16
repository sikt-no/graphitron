package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.service.records.HelloWorldInput;
import no.sikt.graphitron.example.service.records.CustomerEmailsRecordPayload;
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
        // In a real scenario, the service would use the input to perform business logic
        // (e.g. look up a customer) and return a record with the PK set.
        // Graphitron then auto-fetches all remaining fields from the database using that PK.
        var customer = new CustomerRecord();
        customer.setCustomerId(1);
        return customer;
    }

    public List<CustomerRecord> customers() {
        // Returns records with only the PK set. Graphitron batch-fetches remaining fields from DB.
        var c1 = new CustomerRecord();
        c1.setCustomerId(1);
        var c2 = new CustomerRecord();
        c2.setCustomerId(2);
        return List.of(c1, c2);
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
                id, null, firstName, lastName, email, null, null, null, null, null, null, null
        );
    }

    public List<CustomerRecord> allCustomerEmails() {
        throw new IllegalStateException("You are not allowed to access emails");
    }

    public List<CustomerRecord> allCustomerEmails_IllegalArgument() {
        throw new IllegalArgumentException("This is the error message from the IllegalArgumentException");
    }

    public List<CustomerRecord> allCustomerEmails_customException() {
        throw new CustomBusinessException("Custom business rule violated: restricted access");
    }

    public CustomerEmailsRecordPayload allCustomerEmails_recordPayload() {
        throw new IllegalStateException("Record payload exception");
    }

    public List<UpdateCustomerEmailResult> updateCustomerEmail(List<UpdateCustomerEmailRecord> input) {
        // In a real service, this would persist the email update to the database before returning.
        // The service only needs to return the PK, Graphitron re-fetches the @table fields from DB automatically.
        var customer = new CustomerRecord();
        customer.setCustomerId(input.get(0).getCustomerId());
        return List.of(new UpdateCustomerEmailResult(customer));
    }

}
