package no.fellesstudentsystem.graphitron_newtestorder;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.COMPONENT_PATH;
import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.SCHEMA_EXTENSION;

public enum SchemaComponent {
    PAGE_INFO("special/PageInfo"),
    NODE("special/Node"),
    ERROR("special/ERROR"),
    ORDER_DIRECTION("special/OrderDirection"),
    ORDER("special/Order", ORDER_DIRECTION),
    DATE("Date"),

    DUMMY_ENUM("enums/DummyEnum"),
    DUMMY_ENUM_CONVERTED("enums/DummyEnumConverted", Set.of(ReferencedEntry.DUMMY_JOOQ_ENUM)),

    CUSTOMER("basic/Customer"),
    CUSTOMER_TABLE("basic/CustomerTable"),
    CUSTOMER_INPUT_TABLE("basic/CustomerInputTable"),
    CUSTOMER_CONNECTION_ONLY("basic/CustomerConnection", PAGE_INFO),
    CUSTOMER_CONNECTION(CUSTOMER_TABLE, CUSTOMER_CONNECTION_ONLY),
    CUSTOMER_CONNECTION_ORDER(CUSTOMER_CONNECTION_ONLY, ORDER),

    DUMMY_TYPE("basic/DummyType"),
    DUMMY_TYPE_RECORD("basic/DummyTypeRecord", Set.of(DUMMY_RECORD)),
    DUMMY_INPUT("basic/DummyInput"),
    DUMMY_INPUT_RECORD("basic/DummyInputRecord", Set.of(DUMMY_RECORD)),
    DUMMY_CONNECTION("basic/DummyConnection", DUMMY_TYPE, PAGE_INFO),

    SPLIT_QUERY_WRAPPER("SplitQueryWrapper"),

    CITY_INPUTS_JOOQ("records/CityInputsJOOQ"),
    CITY_INPUTS_JAVA("records/CityInputsJava", Set.of(DUMMY_RECORD)),

    CITY0("records/City0"),
    CITY1("records/City1", Set.of(DUMMY_RECORD)),
    WRAPPED_ADDRESS_JAVA("records/WrappedAddressJava", Set.of(DUMMY_RECORD), CITY0, CITY1),
    WRAPPED_ADDRESS_JOOQ("records/WrappedAddressJOOQ", CITY0, CITY1);

    private final Set<String> fileNames;
    private final Set<ReferencedEntry> references;

    SchemaComponent(SchemaComponent... includes) {
        this("", includes);
    }

    SchemaComponent(String path, SchemaComponent... includes) {
        this(path, Set.of(), includes);
    }

    SchemaComponent(String path, Set<ReferencedEntry> references, SchemaComponent... includes) {
        var included = Stream.of(includes).flatMap(it -> it.getPaths().stream());
        if (path == null || path.isEmpty()) {
            fileNames = included.collect(Collectors.toSet());
        } else {
            fileNames = Stream.concat(Stream.of(toFullPath(path)), included).collect(Collectors.toSet());
        }
        this.references = Stream.concat(references.stream(), Stream.of(includes).flatMap(it -> it.getReferences().stream())).collect(Collectors.toSet());
    }

    private String toFullPath(String file) {
        return COMPONENT_PATH + "/" + file + SCHEMA_EXTENSION;
    }

    public Set<String> getPaths() {
        return fileNames;
    }

    public Set<ReferencedEntry> getReferences() {
        return references;
    }
}
