package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

/**
 * Test Java record (POJO) with a jOOQ record field for @nodeId testing.
 * Used to test transformation of @nodeId fields to jOOQ records in @record inputs.
 */
public class NodeIdInputJavaRecord {
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
