package no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions;

import no.sikt.graphitron.jooq.generated.testdata.tables.Address;
import no.sikt.graphitron.jooq.generated.testdata.tables.City;
import no.sikt.graphitron.jooq.generated.testdata.tables.Customer;
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
}
