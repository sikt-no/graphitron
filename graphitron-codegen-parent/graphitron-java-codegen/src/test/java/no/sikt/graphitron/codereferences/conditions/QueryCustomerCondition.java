package no.sikt.graphitron.codereferences.conditions;

import no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.jooq.Condition;

import java.util.List;

public class QueryCustomerCondition {
    public static Condition email(Customer c, String s) {
        return null;
    }

    public static Condition emails(Customer c, List<String> s) {
        return null;
    }

    public static Condition query(Customer c, String s) {
        return null;
    }

    public static Condition id(Customer c, String s) {
        return null;
    }

    public static Condition query(Customer c, String s0, String s1) {
        return null;
    }

    public static Condition queryList(Customer c, List<String> s) {
        return null;
    }

    public static Condition enumInput(Customer c, DummyJOOQEnum e) {
        return null;
    }

    public static Condition enumInputList(Customer c, List<DummyJOOQEnum> e) {
        return null;
    }

    public static Condition queryEnum(Customer c, DummyJOOQEnum e) {
        return null;
    }

    public static Condition queryEnumList(Customer c, List<DummyJOOQEnum> e) {
        return null;
    }

    public static Condition queryCustomerNodeId(Customer c, CustomerRecord s) {
        return null;
    }

    public static Condition queryCustomerIdAndNodeId(Customer c, int customerId, CustomerRecord s) {
        return null;
    }

    public static Condition queryCustomerNodeIds(Customer c, List<CustomerRecord> s) {
        return null;
    }

    public static Condition queryCustomerNodeIdAsString(Customer c, String s) {
        return null;
    }

    public static Condition queryCustomerNodeIdsAsString(Customer c, List<String> s) {
        return null;
    }
}
