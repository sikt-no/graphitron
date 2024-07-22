package no.fellesstudentsystem.graphitron.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

import java.util.List;

public class TestCustomerRecord {
    private String someID, name;
    private List<String> someListID, nameList1, nameList2;
    private CustomerRecord record;
    private List<CustomerRecord> recordList;

    public String getSomeID() {
        return someID;
    }

    public String getName() {
        return name;
    }

    public List<String> getSomeListID() {
        return someListID;
    }

    public List<String> getNameList1() {
        return nameList1;
    }

    public List<String> getNameList2() {
        return nameList2;
    }

    public CustomerRecord getRecord() {
        return record;
    }

    public List<CustomerRecord> getRecordList() {
        return recordList;
    }

    public void setSomeID(String someID) {
        this.someID = someID;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setSomeListID(List<String> someListID) {
        this.someListID = someListID;
    }

    public void setNameList1(List<String> nameList1) {
        this.nameList1 = nameList1;
    }

    public void setNameList2(List<String> nameList2) {
        this.nameList2 = nameList2;
    }

    public void setRecord(CustomerRecord record) {
        this.record = record;
    }

    public void setRecordList(List<CustomerRecord> recordList) {
        this.recordList = recordList;
    }
}
