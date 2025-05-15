package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Customer;
import org.jooq.Condition;

public class CustomerConditions {
    public static Condition activeCustomers(Customer customer) {
        return customer.ACTIVE.eq(1);
    }
}
