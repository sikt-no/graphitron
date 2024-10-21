package no.fellesstudentsystem.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;

import java.util.List;

public class MapperNestedJavaRecord {
    Customer customer;
    IDJavaRecord dummyRecord;
    List<Customer> customerList;

    public Customer getCustomer() {
        return customer;
    }

    public IDJavaRecord getDummyRecord() {
        return dummyRecord;
    }

    public void setDummyRecord(IDJavaRecord dummyRecord) {
        this.dummyRecord = dummyRecord;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public List<Customer> getCustomerList() {
        return customerList;
    }

    public void setCustomerList(List<Customer> customerList) {
        this.customerList = customerList;
    }
}
