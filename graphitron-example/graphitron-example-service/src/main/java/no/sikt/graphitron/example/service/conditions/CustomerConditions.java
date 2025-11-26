package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Customer;
import org.jooq.Condition;
import org.jooq.impl.DSL;

public class CustomerConditions {
    public static Condition activeCustomers(Customer customer) {
        return customer.ACTIVE.eq(1);
    }

    public static Condition inactiveCustomers(Customer customer) {
        return customer.ACTIVE.eq(0);
    }

    public static Condition inactiveCustomers(Customer customer, String lastNameStartingWith) {
        return inactiveCustomers(customer)
                .and(lastNameStartingWith != null ? customer.LAST_NAME.startsWith(lastNameStartingWith) : DSL.noCondition());
    }
}
