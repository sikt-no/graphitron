package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Address;
import no.sikt.graphitron.example.generated.jooq.tables.City;
import no.sikt.graphitron.example.generated.jooq.tables.Customer;
import org.jooq.Condition;
import org.jooq.impl.DSL;

public class CityConditions {
    public static Condition customersForCityViaAddresses(Address address, Customer customer) {
        return DSL.trueCondition();
    }

    /**
     * Reaches a table with no key in the reference path, so the generated query has to alias the city itself and
     * correlate it back by primary key. Both the list query and the count query behind {@code totalCount} must bind
     * that alias.
     */
    public static Condition addressesForCity(City city, Address address) {
        return city.CITY_ID.eq(address.CITY_ID);
    }
}
