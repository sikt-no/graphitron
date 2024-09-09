package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.conditions.*;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.enums.RatingTest;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;

public enum TestReferenceSet {
    // Enums
    ENUM_RATING("RATING_TEST", RatingTest.class),

    // Conditions
    CONDITION_STORE_CUSTOMER("TEST_STORE_CUSTOMER", StoreTestConditions.class),
    CONDITION_FILM_RATING("TEST_FILM_RATING", RatingTestConditions.class),

    // Services
    SERVICE_CUSTOMER("TEST_CUSTOMER", TestCustomerService.class),

    // Records
    RECORD_CUSTOMER("TEST_CUSTOMER_RECORD", TestCustomerRecord.class);

    private final ExternalReference reference;

    TestReferenceSet(String name, Class<?> referenceClass) {
        this.reference = new ExternalClassReference(name, referenceClass);
    }

    public ExternalReference get() {
        return reference;
    }
}
