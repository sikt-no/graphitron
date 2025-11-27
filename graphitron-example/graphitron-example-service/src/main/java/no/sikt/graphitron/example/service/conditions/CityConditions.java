package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Address;
import no.sikt.graphitron.example.generated.jooq.tables.Customer;
import org.jooq.Condition;
import org.jooq.impl.DSL;

public class CityConditions {
    public static Condition customersForCityViaAddresses(Address address, Customer customer) {
        return DSL.trueCondition();
    }
}
