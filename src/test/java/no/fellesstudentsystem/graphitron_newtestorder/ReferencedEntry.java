package no.fellesstudentsystem.graphitron_newtestorder;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordCustomerCondition;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.RecordStaffCondition;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.ReferenceCustomerCondition;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.ReferenceFilmCondition;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyCondition;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyJOOQEnum;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyRecord;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyService;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.*;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.services.*;

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
    RESOLVER_FETCH_SERVICE("RESOLVER_FETCH_SERVICE", ResolverFetchService.class),

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
    REFERENCE_CUSTOMER_CONDITION("REFERENCE_CUSTOMER_CONDITION", ReferenceCustomerCondition.class),
    REFERENCE_FILM_CONDITION("REFERENCE_FILM_CONDITION", ReferenceFilmCondition.class),
    QUERY_FETCH_CONDITION("QUERY_FETCH_CONDITION", QueryCustomerCondition.class),
    QUERY_FETCH_STAFF_CONDITION("QUERY_FETCH_STAFF_CONDITION", QueryStaffCondition.class);

    private final ExternalReference reference;

    ReferencedEntry(String name, Class<?> referenceClass) {
        this.reference = new ExternalClassReference(name, referenceClass);
    }

    public ExternalReference get() {
        return reference;
    }
}
