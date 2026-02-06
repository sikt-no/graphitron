package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;

/**
 * Input POJO for testing @nodeId field transformation to jOOQ record.
 * The customer field is a jOOQ CustomerRecord, which will be populated
 * from the base64-encoded node ID in the GraphQL input.
 */
public class NodeIdToJooqInput {
    private String name;
    private CustomerRecord customer;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CustomerRecord getCustomer() {
        return customer;
    }

    public void setCustomer(CustomerRecord customer) {
        this.customer = customer;
    }
}
