package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.AddressRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;

import java.util.List;

public class MockUpdateAddressAndCustomerResultRecord {
    private AddressRecord myAddress;
    private AddressRecord address;
    private List<CustomerRecord> customers;


    public AddressRecord getAddress() {
        return address;
    }

    public void setAddress(AddressRecord address) {
        this.address = address;
    }


    public List<CustomerRecord> getCustomers() {
        return customers;
    }

    public void setCustomers(List<CustomerRecord> customers) {
        this.customers = customers;
    }

    public AddressRecord getMyAddress() {
        return myAddress;
    }

    public void setMyAddress(AddressRecord myAddress) {
        this.myAddress = myAddress;
    }
}
