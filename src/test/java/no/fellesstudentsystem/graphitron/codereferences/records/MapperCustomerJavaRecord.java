package no.fellesstudentsystem.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

import java.util.List;

public class MapperCustomerJavaRecord {
    private String someID, name;
    private List<String> someListID, nameList1, nameList2;
    private MapperCustomerInnerJavaRecord edit2A, customerInnerJavaRecord;
    private MapperCustomerInnerJavaRecord customerInnerJavaRecordList;
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

    public MapperCustomerInnerJavaRecord getEdit2A() {
        return edit2A;
    }

    public MapperCustomerInnerJavaRecord getTestCustomerInnerRecord() {
        return customerInnerJavaRecord;
    }

    public MapperCustomerInnerJavaRecord getTestCustomerInnerRecordList() {
        return customerInnerJavaRecordList;
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

    public void setEdit2A(MapperCustomerInnerJavaRecord edit2A) {
        this.edit2A = edit2A;
    }

    public void setTestCustomerInnerRecord(MapperCustomerInnerJavaRecord customerInnerJavaRecord) {
        this.customerInnerJavaRecord = customerInnerJavaRecord;
    }

    public void setTestCustomerInnerRecordList(MapperCustomerInnerJavaRecord customerInnerJavaRecordList) {
        this.customerInnerJavaRecordList = customerInnerJavaRecordList;
    }

    public void setRecord(CustomerRecord record) {
        this.record = record;
    }

    public void setRecordList(List<CustomerRecord> recordList) {
        this.recordList = recordList;
    }
}
