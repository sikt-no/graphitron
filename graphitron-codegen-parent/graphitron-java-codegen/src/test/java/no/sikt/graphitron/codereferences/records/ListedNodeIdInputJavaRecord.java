package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

import java.util.List;

public class ListedNodeIdInputJavaRecord {
    private String name;
    private List<CustomerRecord> customer;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<CustomerRecord> getCustomer() {
        return customer;
    }

    public void setCustomer(List<CustomerRecord> customer) {
        this.customer = customer;
    }
}
