package no.fellesstudentsystem.graphitron.conditions;

import no.sikt.graphitron.jooq.generated.testdata.tables.Customer;
import no.sikt.graphitron.jooq.generated.testdata.tables.Store;
import org.jooq.Condition;

public class StoreTestConditions {
    public static Condition storeCustomer(Store store, Customer customer) {
        return null;
    }

    public static Condition customerStore(Customer customer, Store store) {
        return null;
    }
}
