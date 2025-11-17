package no.sikt.graphitron.codereferences.conditions;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Address;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.City;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Payment;
import org.jooq.Condition;

public class ReferenceCustomerCondition {
    public static Condition addressCustomer(Customer c, Address a) {
        return null;
    }
    public static Condition addressCity(City c, Address a) {
        return null;
    }
    public static Condition cityCustomer(Customer cu, City ci) {
        return null;
    }
    public static Condition cityAddress(Address a, City c) {
        return null;
    }
    public static Condition district(Customer c, Address a) {
        return null;
    }
    public static Condition email(Address a, Customer c) {
        return null;
    }
    public static Condition addressId(Customer c, Address a) {
        return null;
    }
    public static Condition payments(Customer c, Payment p) {
        return null;
    }
}
