package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Customer;
import no.sikt.graphitron.example.generated.jooq.tables.Payment;
import no.sikt.graphitron.example.generated.jooq.tables.Staff;
import org.jooq.Condition;

import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.trueCondition;

public class PersonWithEmailConditions {
    public static Condition mostImportantCustomersForPayment(Payment payment, Staff staff) {
        return staff.EMAIL.startsWith("Jon");
    }

    public static Condition mostImportantCustomersForPayment(Payment payment, Customer customer) {
        return customer.EMAIL.startsWith("BARBARA");
    }
}
