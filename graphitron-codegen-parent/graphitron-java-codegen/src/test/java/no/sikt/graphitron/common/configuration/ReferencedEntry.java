package no.sikt.graphitron.common.configuration;

import no.sikt.graphitron.codereferences.conditions.*;
import no.sikt.graphitron.codereferences.dummyreferences.DummyCondition;
import no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum;
import no.sikt.graphitron.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphitron.codereferences.dummyreferences.DummyService;
import no.sikt.graphitron.codereferences.records.*;
import no.sikt.graphitron.codereferences.services.*;
import no.sikt.graphitron.configuration.externalreferences.ExternalClassReference;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;

public enum ReferencedEntry {
    // Dummy service.
    DUMMY_SERVICE("DUMMY_SERVICE", DummyService.class),

    // Dummy record.
    DUMMY_RECORD("DUMMY_RECORD", DummyRecord.class),

    // Dummy condition.
    DUMMY_CONDITION("DUMMY_CONDITION", DummyCondition.class),

    // Dummy enum.
    DUMMY_JOOQ_ENUM("DUMMY_JOOQ_ENUM", DummyJOOQEnum.class),

    // Mapping services.
    MAPPER_FETCH_SERVICE("MAPPER_FETCH_SERVICE", MapperFetchService.class),
    MAPPER_ID_SERVICE("MAPPER_ID_SERVICE", IDMapperService.class),
    CONTEXT_SERVICE("CONTEXT_SERVICE", ContextService.class),
    RESOLVER_FETCH_SERVICE("RESOLVER_FETCH_SERVICE", ResolverFetchService.class),

    // Mutation services.
    RESOLVER_MUTATION_SERVICE("RESOLVER_MUTATION_SERVICE", ResolverMutationService.class),

    // Mapping records.
    MAPPER_RECORD_CITY("MAPPER_RECORD_CITY", MapperCityJavaRecord.class),
    MAPPER_RECORD_ADDRESS("MAPPER_RECORD_ADDRESS", MapperAddressJavaRecord.class),
    MAPPER_RECORD_ENUM("MAPPER_RECORD_ENUM", MapperEnumRecord.class),
    ID_RECORD("ID_RECORD", IDJavaRecord.class),
    NESTED_RECORD("NESTED_RECORD", MapperNestedJavaRecord.class),

    // Input record services.
    JAVA_RECORD_FETCH_SERVICE("JAVA_RECORD_FETCH_SERVICE", JavaRecordInputFetchService.class),
    JOOQ_RECORD_FETCH_SERVICE("JOOQ_RECORD_FETCH_SERVICE", JOOQRecordInputFetchService.class),
    JAVA_RECORD_CUSTOMER("JAVA_RECORD_CUSTOMER", CustomerJavaRecord.class),
    JAVA_RECORD_STAFF_INPUT1("JAVA_RECORD_STAFF_INPUT1", StaffInput1JavaRecord.class),
    JAVA_RECORD_STAFF_INPUT2("JAVA_RECORD_STAFF_INPUT2", StaffInput2JavaRecord.class),
    JAVA_RECORD_STAFF_INPUT3("JAVA_RECORD_STAFF_INPUT3", StaffInput3JavaRecord.class),
    JAVA_RECORD_STAFF_NAME("JAVA_RECORD_STAFF_NAME", StaffNameJavaRecord.class),

    // Input record conditions.
    RECORD_FETCH_CONDITION("RECORD_FETCH_CONDITION", RecordCustomerCondition.class),
    RECORD_FETCH_STAFF_CONDITION("RECORD_FETCH_STAFF_CONDITION", RecordStaffCondition.class),

    // Query conditions.
    REFERENCE_CUSTOMER_CONDITION("REFERENCE_CUSTOMER_CONDITION", ReferenceCustomerCondition.class),
    REFERENCE_FILM_CONDITION("REFERENCE_FILM_CONDITION", ReferenceFilmCondition.class),
    QUERY_FETCH_CONDITION("QUERY_FETCH_CONDITION", QueryCustomerCondition.class),
    QUERY_FETCH_STAFF_CONDITION("QUERY_FETCH_STAFF_CONDITION", QueryStaffCondition.class),
    QUERY_FETCH_ADDRESS_INTERFACE_CONDITION("QUERY_FETCH_ADDRESS_INTERFACE_CONDITION", QueryAddressInterfaceCondition.class),
    QUERY_FETCH_PAYMENT_INTERFACE_CONDITION("QUERY_FETCH_PAYMENT_INTERFACE_CONDITION", QueryPaymentInterfaceCondition.class),
    REFERENCE_PG_USER_MAPPING_CONDITION("REFERENCE_PG_USER_MAPPING_CONDITION", PgUserMappingCondition.class),
    CONTEXT_CONDITION("CONTEXT_CONDITION", ContextCondition.class),
    PAYMENT_CONDITION("PAYMENT_CONDITION", PaymentCondition.class),

    // NodeId to jOOQ record test records.
    NODEID_INPUT_JAVA_RECORD("NODEID_INPUT_JAVA_RECORD", NodeIdInputJavaRecord.class),
    RENTAL_INPUT_JAVA_RECORD("RENTAL_INPUT_JAVA_RECORD", RentalInputJavaRecord.class),
    FILM_ACTOR_INPUT_JAVA_RECORD("FILM_ACTOR_INPUT_JAVA_RECORD", FilmActorInputJavaRecord.class),
    COMPOSITE_KEY_INPUT_JAVA_RECORD("COMPOSITE_KEY_INPUT_JAVA_RECORD", CompositeKeyInputJavaRecord.class),
    FILM_JAVA_RECORD("FILM_JAVA_RECORD", FilmJavaRecord.class);

    private final ExternalReference reference;

    ReferencedEntry(String name, Class<?> referenceClass) {
        this.reference = new ExternalClassReference(name, referenceClass);
    }

    public ExternalReference get() {
        return reference;
    }
}
