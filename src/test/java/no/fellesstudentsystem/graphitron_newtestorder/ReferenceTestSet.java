package no.fellesstudentsystem.graphitron_newtestorder;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyMapperRecord;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyMapperService;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperAddressJavaRecord;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperCityJavaRecord;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperCustomerInnerJavaRecord;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperCustomerJavaRecord;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.services.MapperFetchCustomerService;

public enum ReferenceTestSet {
    // Dummy service.
    MAPPER_DUMMY_SERVICE("MAPPER_DUMMY_SERVICE", DummyMapperService.class),

    // Dummy record.
    MAPPER_DUMMY_RECORD("MAPPER_DUMMY_RECORD", DummyMapperRecord.class),

    // Mapping services.
    MAPPER_FETCH_CUSTOMER_SERVICE("MAPPER_FETCH_CUSTOMER_SERVICE", MapperFetchCustomerService.class),

    // Mapping records.
    MAPPER_RECORD_CUSTOMER("MAPPER_RECORD_CUSTOMER", MapperCustomerJavaRecord.class),
    MAPPER_RECORD_CUSTOMER_INNER("MAPPER_RECORD_CUSTOMER_INNER", MapperCustomerInnerJavaRecord.class),
    MAPPER_RECORD_CITY("MAPPER_RECORD_CITY", MapperCityJavaRecord.class),
    MAPPER_RECORD_ADDRESS("MAPPER_RECORD_ADDRESS", MapperAddressJavaRecord.class);

    private final ExternalReference reference;

    ReferenceTestSet(String name, Class<?> referenceClass) {
        this.reference = new ExternalClassReference(name, referenceClass);
    }

    public ExternalReference get() {
        return reference;
    }
}
