package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.conditions.*;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.enums.RatingListTest;
import no.fellesstudentsystem.graphitron.enums.RatingTest;
import no.fellesstudentsystem.graphitron.records.*;
import no.fellesstudentsystem.graphitron.services.*;
import no.fellesstudentsystem.graphitron.transforms.SomeTransform;

public enum TestReferenceSet {
    // Enums
    ENUM_RATING("RATING_TEST", RatingTest.class),
    ENUM_RATING_LIST("TEST_ENUM_RATING_LIST", RatingListTest.class),

    // Conditions
    CONDITION_STORE_CUSTOMER("TEST_STORE_CUSTOMER", StoreTestConditions.class),
    CONDITION_CUSTOMER_ADDRESS("TEST_CUSTOMER_ADDRESS", CustomerTestConditions.class),
    CONDITION_FILM_ACTOR("TEST_FILM_ACTOR", FilmActorTestConditions.class),
    CONDITION_CITY("TEST_CITY", CityTestConditions.class),
    CONDITION_FILM_RATING("TEST_FILM_RATING", RatingTestConditions.class),

    // Services
    SERVICE_CUSTOMER("TEST_CUSTOMER", TestCustomerService.class),
    SERVICE_FETCH_CUSTOMER("TEST_FETCH_CUSTOMER", TestFetchCustomerService.class),
    SERVICE_FILM("TEST_FILM", TestFilmService.class),
    SERVICE_FETCH_CITY("TEST_FETCH_CITY", TestFetchCityService.class),

    // Records
    RECORD_CUSTOMER("TEST_CUSTOMER_RECORD", TestCustomerRecord.class),
    RECORD_ADDRESS("TEST_ADDRESS_RECORD", TestAddressRecord.class),
    RECORD_CUSTOMER_INNER("TEST_CUSTOMER_INNER_RECORD", TestCustomerInnerRecord.class),
    RECORD_CUSTOMER_RESPONSE_1("TEST_CUSTOMER_RESPONSE1", EditCustomerResponse1.class),
    RECORD_CUSTOMER_RESPONSE_2("TEST_CUSTOMER_RESPONSE2", EditCustomerResponse2.class),
    RECORD_CUSTOMER_RESPONSE_3("TEST_CUSTOMER_RESPONSE3", EditCustomerResponse3.class),
    RECORD_CUSTOMER_RESPONSE_4("TEST_CUSTOMER_RESPONSE4", EditCustomerResponse4.class),
    RECORD_ADDRESS_RESPONSE("TEST_CUSTOMER_ADDRESS_RESPONSE", EditCustomerAddressResponse.class),
    RECORD_FILM("TEST_FILM_RECORD", TestFilmRecord.class),
    RECORD_CITY("TEST_CITY_RECORD", TestCityRecord.class),
    RECORD_ID("TEST_ID_RECORD", TestIDRecord.class),

    // Transform
    TRANSFORM_0("TEST_TRANSFORM", SomeTransform.class);

    private final ExternalReference reference;

    TestReferenceSet(String name, Class<?> referenceClass) {
        this.reference = new ExternalClassReference(name, referenceClass);
    }

    public ExternalReference get() {
        return reference;
    }
}
