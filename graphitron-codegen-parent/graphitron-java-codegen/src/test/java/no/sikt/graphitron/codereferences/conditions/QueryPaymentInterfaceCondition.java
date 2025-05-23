package no.sikt.graphitron.codereferences.conditions;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.PaymentP2007_01;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.PaymentP2007_02;
import org.jooq.Condition;

public class QueryPaymentInterfaceCondition {
    public static Condition payments(PaymentP2007_01 payment, String customerId) {
        return null;
    }

    public static Condition payments(PaymentP2007_02 payment, String customerId) {
        return null;
    }
}
