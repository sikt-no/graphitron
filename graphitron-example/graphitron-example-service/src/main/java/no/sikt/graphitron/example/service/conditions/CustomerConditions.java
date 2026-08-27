package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Customer;
import no.sikt.graphitron.example.generated.jooq.tables.Payment;
import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import org.jooq.Condition;
import org.jooq.impl.DSL;

import java.util.List;

public class CustomerConditions {
    /**
     * Reaches a table with no key in the reference path, over a relation with enough rows per customer that a page of
     * payments is smaller than the count behind it. That gap is what separates counting the whole set from counting
     * the page.
     */
    public static Condition paymentsForCustomer(Customer customer, Payment payment) {
        return customer.CUSTOMER_ID.eq(payment.CUSTOMER_ID);
    }

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

    public static Condition customerWithNextCustomerId(Customer customer, CustomerRecord id) {
        return customer.CUSTOMER_ID.eq(id.getCustomerId() + 1);
    }

    public static Condition customerWithSumOfCustomerIds(Customer customer, List<CustomerRecord> customerNodeIds) {
        return customer.CUSTOMER_ID.eq(customerNodeIds.stream().map(CustomerRecord::getCustomerId).reduce(0, Integer::sum));
    }

    public static Condition customerWithNextCustomerIdWithNodeIdString(Customer customer, String nodeId) {
        return DSL.falseCondition(); // This method is only added to ensure we don't get a compilation error
    }
}
