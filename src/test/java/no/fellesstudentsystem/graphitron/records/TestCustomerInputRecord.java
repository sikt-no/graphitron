package no.fellesstudentsystem.graphitron.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

import java.util.List;

public class TestCustomerInputRecord {
    private String someID, name;
    private List<String> someListID, nameList1, nameList2;
    private TestCustomerInnerInputRecord edit2A, testCustomerInnerInputRecord;
    private TestCustomerInnerInputRecord testCustomerInnerInputRecordList;
    private CustomerRecord record;
    private List<CustomerRecord> recordList;

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

    public void setEdit2A(TestCustomerInnerInputRecord edit2A) {
        this.edit2A = edit2A;
    }

    public void setTestCustomerInnerInputRecord(TestCustomerInnerInputRecord testCustomerInnerInputRecord) {
        this.testCustomerInnerInputRecord = testCustomerInnerInputRecord;
    }

    public void setTestCustomerInnerInputRecordList(TestCustomerInnerInputRecord testCustomerInnerInputRecordList) {
        this.testCustomerInnerInputRecordList = testCustomerInnerInputRecordList;
    }

    public void setRecord(CustomerRecord record) {
        this.record = record;
    }

    public void setRecordList(List<CustomerRecord> recordList) {
        this.recordList = recordList;
    }
}
