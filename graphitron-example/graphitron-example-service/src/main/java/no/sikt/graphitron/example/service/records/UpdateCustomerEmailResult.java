package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;

public final class UpdateCustomerEmailResult {
    private final CustomerRecord customerEmail;

    public UpdateCustomerEmailResult(CustomerRecord customer) {
        this.customerEmail = customer;
    }

    public CustomerRecord getCustomerEmail() {
        return customerEmail;
    }

}
