package no.sikt.graphitron.codereferences.conditions;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Payment;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Staff;
import org.jooq.Condition;

public class PaymentCondition {
    public static Condition paymentStaff(Payment p, Staff s) {
        return null;
    }
}
