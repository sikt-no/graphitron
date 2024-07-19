package no.fellesstudentsystem.graphitron_newtestorder.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.AddressRecord;

public class QueryCustomerJavaRecord {
    private String someID, otherID;
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
}
