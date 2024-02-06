package no.fellesstudentsystem.graphitron.records;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class TestCustomerInnerInputRecord {
    private CustomerRecord someRecord;
    private CustomerRecord someRecordList;
    private Integer someInt;

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
