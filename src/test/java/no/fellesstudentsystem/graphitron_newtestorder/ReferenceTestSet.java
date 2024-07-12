package no.fellesstudentsystem.graphitron_newtestorder;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyCondition;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyEnum;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyRecord;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyService;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.*;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.services.JOOQRecordInputFetchService;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.services.JavaRecordInputFetchService;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.services.MapperFetchService;

public enum ReferenceTestSet {
    // Dummy service.
    DUMMY_SERVICE("DUMMY_SERVICE", DummyService.class),

    // Dummy record.
    DUMMY_RECORD("DUMMY_RECORD", DummyRecord.class),

    // Dummy condition.
    DUMMY_CONDITION("DUMMY_CONDITION", DummyCondition.class),

    // Dummy enum.
    DUMMY_ENUM("DUMMY_ENUM", DummyEnum.class),

    // Mapping services.
    MAPPER_FETCH_SERVICE("MAPPER_FETCH_SERVICE", MapperFetchService.class),

    // Mapping records.
    MAPPER_RECORD_CUSTOMER("MAPPER_RECORD_CUSTOMER", MapperCustomerJavaRecord.class),
    MAPPER_RECORD_CUSTOMER_INNER("MAPPER_RECORD_CUSTOMER_INNER", MapperCustomerInnerJavaRecord.class),
    MAPPER_RECORD_CITY("MAPPER_RECORD_CITY", MapperCityJavaRecord.class),
    MAPPER_RECORD_ADDRESS("MAPPER_RECORD_ADDRESS", MapperAddressJavaRecord.class),
    MAPPER_RECORD_FILM("MAPPER_RECORD_FILM", MapperFilmJavaRecord.class),

    // Input record services.
    JAVA_RECORD_FETCH_SERVICE("JAVA_RECORD_FETCH_SERVICE", JavaRecordInputFetchService.class),
    JOOQ_RECORD_FETCH_SERVICE("JOOQ_RECORD_FETCH_SERVICE", JOOQRecordInputFetchService.class),
    JAVA_RECORD_FETCH_QUERY("JAVA_RECORD_FETCH_QUERY", QueryCustomerJavaRecord.class),

    // Input record conditions.
    RECORD_FETCH_CONDITION("RECORD_FETCH_CONDITION", QueryCustomerCondition.class);

    private final ExternalReference reference;

    ReferenceTestSet(String name, Class<?> referenceClass) {
        this.reference = new ExternalClassReference(name, referenceClass);
    }

    public ExternalReference get() {
        return reference;
    }
}
