package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.AddressRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;

public class MockUpdateAddressAndCustomerResultRecord {
    private AddressRecord myAddress;
    private AddressRecord address;

    public AddressRecord getAddress() {
        return address;
    }

    public void setAddress(AddressRecord address) {
        this.address = address;
    }

    private CustomerRecord customer;


    public CustomerRecord getCustomer() {
        return customer;
    }

    public void setCustomer(CustomerRecord customer) {
        this.customer = customer;
    }

    public AddressRecord getMyAddress() {
        return myAddress;
    }

    public void setMyAddress(AddressRecord myAddress) {
        this.myAddress = myAddress;
    }
}
