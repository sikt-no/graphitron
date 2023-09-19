package no.fellesstudentsystem.graphitron.conditions;


import no.sikt.graphitron.jooq.generated.testdata.tables.City;
import org.jooq.Condition;

import java.util.List;

/**
 * Fake service for condition tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class CityTestConditions {
    public static Condition cityName(City city, String name) {
        return null;
    }

    public static Condition cityNames(City city, List<String> names) {
        return null;
    }

    public static Condition cityAll(City city, String countryId, List<String> names) {
        return null;
    }

    public static Condition cityInputAll(City city, String countryId, String name, String cityId) {
        return null;
    }
}