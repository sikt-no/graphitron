package no.sikt.graphitron.codereferences.conditions;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import org.jooq.Condition;

public class ContextCondition {
    public static Condition query(Customer c, String ctxField) {
        return null;
    }
    public static Condition query(Customer c, String email, String ctxField) {
        return null;
    }
    public static Condition email(Customer c, String email, String ctxField) {
        return null;
    }
    public static Condition email(Customer c, String email, String ctxField1, String ctxField2) {
        return null;
    }
}
