package no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions;

import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.QueryCustomerJavaRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.Customer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.Condition;

import java.util.List;

public class QueryCustomerCondition {
    public static Condition customerString(Customer customer, String s) {
        return null;
    }

    public static Condition customerJOOQRecord(Customer customer, CustomerRecord record) {
        return null;
    }

    public static Condition customerJOOQRecordList(Customer customer) {
        return null;
    }

    public static Condition customerJavaRecord(Customer customer, QueryCustomerJavaRecord record) {
        return null;
    }
    public static Condition customerJavaRecordList(Customer customer, List<QueryCustomerJavaRecord> record) {
        return null;
    }
}
