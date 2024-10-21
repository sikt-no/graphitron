package no.fellesstudentsystem.graphitron.codereferences.conditions;

import no.fellesstudentsystem.graphitron.codereferences.records.CustomerJavaRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.jooq.Condition;

import java.util.List;

public class RecordCustomerCondition {
    public static Condition customerString(Customer customer, String s) {
        return null;
    }

    public static Condition customerJOOQRecord(Customer customer, CustomerRecord record) {
        return null;
    }

    public static Condition customerJOOQRecordList(Customer customer, List<CustomerRecord> record) {
        return null;
    }

    public static Condition customerJavaRecord(Customer customer, CustomerJavaRecord record) {
        return null;
    }
    public static Condition customerJavaRecordList(Customer customer, List<CustomerJavaRecord> record) {
        return null;
    }
}
