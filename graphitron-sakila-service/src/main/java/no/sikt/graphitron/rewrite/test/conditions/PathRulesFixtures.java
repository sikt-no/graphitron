package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Address;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Customer;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Staff;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Store;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Condition methods for the path rules the fact documents {@code scenarios/path-rules} and
 * {@code defects/path-rule-defects} name: one written condition resolved by the overload each step's two
 * tables take, one method reused across the members of a target with no table, and the overload
 * sets that leave a step with no declaration or with two tying.
 *
 * <p>Parameter 0 is the table the step departs and parameter 1 the one it arrives at, the order the
 * emitter calls a condition in. The bodies are not the point.
 */
public final class PathRulesFixtures {

    private PathRulesFixtures() {}

    /** A customer's address: the overload a step from customer takes. */
    public static Condition homeAddress(Customer customer, Address address) {
        return customer.ADDRESS_ID.eq(address.ADDRESS_ID);
    }

    /** A staff member's address: the overload a step from staff takes. */
    public static Condition homeAddress(Staff staff, Address address) {
        return staff.ADDRESS_ID.eq(address.ADDRESS_ID);
    }

    /** Declared for customer alone, so a step from staff to address has no overload. */
    public static Condition customerOnlyAddress(Customer customer, Address address) {
        return customer.ADDRESS_ID.eq(address.ADDRESS_ID);
    }

    /** A store's customers. */
    public static Condition storePerson(Store store, Customer customer) {
        return store.STORE_ID.eq(customer.STORE_ID);
    }

    /** A store's staff. */
    public static Condition storePerson(Store store, Staff staff) {
        return store.STORE_ID.eq(staff.STORE_ID);
    }

    /** Exact at the departure only; ties with the other declaration for customer to address. */
    public static Condition tiedAddress(Customer customer, Table<?> address) {
        return DSL.noCondition();
    }

    /** Exact at the arrival only; ties with the other declaration for customer to address. */
    public static Condition tiedAddress(Table<?> customer, Address address) {
        return DSL.noCondition();
    }
}
