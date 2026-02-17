package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.AddressRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

import java.util.List;

public class CustomerJavaRecord {
    private String someID, addressId, otherID, email;
    private List<String> idList;
    private AddressRecord address;
    private CustomerRecord customer;

    public String getSomeID() {
        return someID;
    }

    public void setSomeID(String someID) {
        this.someID = someID;
    }

    public String getOtherID() {
        return otherID;
    }

    public void setOtherID(String otherID) {
        this.otherID = otherID;
    }

    public AddressRecord getAddress() {
        return address;
    }

    public void setAddress(AddressRecord address) {
        this.address = address;
    }

    public String getAddressId() {
        return addressId;
    }

    public void setAddressId(String addressId) {
        this.addressId = addressId;
    }

    public List<String> getIdList() {
        return idList;
    }

    public void setIdList(List<String> idList) {
        this.idList = idList;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public CustomerRecord getCustomer() {
        return customer;
    }

    public void setCustomer(CustomerRecord customer) {
        this.customer = customer;
    }
}
