package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;

import java.util.List;

public class CustomerEmailsRecordPayload {
    private List<CustomerRecord> customers;

    public List<CustomerRecord> getCustomers() {
        return customers;
    }

    public void setCustomers(List<CustomerRecord> customers) {
        this.customers = customers;
    }
}
