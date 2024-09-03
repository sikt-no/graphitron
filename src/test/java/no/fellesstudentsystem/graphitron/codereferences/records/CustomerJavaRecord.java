package no.fellesstudentsystem.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.AddressRecord;

import java.util.List;

public class CustomerJavaRecord {
    private String someID, addressId, otherID;
    private List<String> idList;
    private AddressRecord address;

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
}
