package no.sikt.graphitron.codereferences.services;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;

public class CustomerTableMethod {
    public Customer customerTable(Customer customer, String firstName) {
        return customer.where(Customer.CUSTOMER.FIRST_NAME.eq(firstName));
    }
    public Customer customerTable(Customer customer) {
        return customer;
    }
}
