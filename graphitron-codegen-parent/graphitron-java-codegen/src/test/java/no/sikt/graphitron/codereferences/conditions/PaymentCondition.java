package no.sikt.graphitron.codereferences.conditions;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Payment;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Rental;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Staff;
import org.jooq.Condition;
import org.jooq.impl.DSL;

public class PaymentCondition {
    public static Condition paymentStaff(Payment p, Staff s) {
        return null;
    }

    public static Condition rentalStaff(Rental r, Staff s) {
        return r.STAFF_ID.eq(s.STAFF_ID);
    }
}
