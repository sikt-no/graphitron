package no.fellesstudentsystem.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class MapperCustomerInnerJavaRecord {
    private CustomerRecord someRecord;
    private CustomerRecord someRecordList;
    private Integer someInt;

    public CustomerRecord getSomeRecord() {
        return someRecord;
    }

    public CustomerRecord getSomeRecordList() {
        return someRecordList;
    }

    public Integer getSomeInt() {
        return someInt;
    }

    public void setSomeRecord(CustomerRecord someRecord) {
        this.someRecord = someRecord;
    }

    public void setSomeRecordList(CustomerRecord someRecordList) {
        this.someRecordList = someRecordList;
    }

    public void setSomeInt(Integer someInt) {
        this.someInt = someInt;
    }
}
