package no.sikt.graphitron.dto;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.dto.InterfaceDTOGenerator;
import no.sikt.graphitron.generators.dto.TypeDTOGenerator;
import no.sikt.graphitron.generators.dto.UnionDTOGenerator;
import no.sikt.graphitron.validation.InvalidSchemaException;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.JAVA_RECORD_CUSTOMER;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIP:
 * Test class for DTOs when splitQueries use foreign key fields instead of previous primary key (unless there is no FK).
 * Note that this is not supported or used yet so there may be some errors in this test class.
 */
public class DTOSplitQueryTest extends DTOGeneratorTest {
    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new TypeDTOGenerator(schema), new InterfaceDTOGenerator(schema), new UnionDTOGenerator(schema));
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setAlwaysUsePrimaryKeyInSplitQueries(false);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setAlwaysUsePrimaryKeyInSplitQueries(true);
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "splitQuery";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(JAVA_RECORD_CUSTOMER);
    }

    @Test
    @DisplayName("Scalar field with splitQuery referencing another table should store foreign key fields")
    void scalarReference() {
        assertGeneratedContentMatches("scalarReference");
    }

    @Test
    @DisplayName("Field with splitQuery referencing another table should store foreign key fields")
    void reference() {
        assertGeneratedContentContains("reference",
                "VacationRecord vacationKey",
                "VacationDestination(VacationRecord vacation_destination_vacation_fkey)",
                "this.vacationKey = vacation_destination_vacation_fkey"
        );
    }

    @Test
    @DisplayName("Listed field stores referenced table's key fields")
    void listedReference() {
        assertGeneratedContentContains("listedReference",
                "VacationDestinationRecord destinationKey",
                "Vacation(VacationDestinationRecord vacation_destination_vacation_fkey)",
                "this.destinationKey = vacation_destination_vacation_fkey"
        );
    }

    @Test
    @DisplayName("Reverse reference stores referenced table's key fields")
    void reverseReference() {
        assertGeneratedContentContains("reverseReference",
                "VacationDestinationRecord destinationKey",
                "Vacation(VacationDestinationRecord vacation_destination_vacation_fkey)",
                "this.destinationKey = vacation_destination_vacation_fkey"
        );
    }

    @Test
    @DisplayName("Connection field stores foreign key fields")
    void connectionReference() {
        assertGeneratedContentContains("connectionReference", Set.of(CUSTOMER_TABLE, CUSTOMER_CONNECTION),
                "Store(CustomerRecord customer_store_id_fkey)",
                "this.customersKey = customer_store_id_fkey"
        );
    }

    @Test
    @DisplayName("Condition path falls back to primary key fields")
    void conditionPath() {
        assertGeneratedContentContains("conditionPath",
                "VacationDestination(VacationDestinationRecord vacation_destination_pkey) " +
                        "{ this.vacation_destination_pkey = vacation_destination_pkey; this.someStringsKey = vacation_destination_pkey; }",
                "private VacationDestinationRecord someStringsKey"
        );
    }

    @Test
    @DisplayName("Field reference with both condition and implicit key should store foreign key fields")
    void conditionWithImplicitKey() {
        assertGeneratedContentContains("conditionWithImplicitKey",
                "VacationRecord vacationsKey",
                "this.vacationsKey = vacation_destination_vacation_fkey"
        );
    }

    @Test
    @DisplayName("Field reference with both condition and no implicit key should store primary key fields")
    void conditionWithoutImplicitKey() {
        assertGeneratedContentContains("conditionWithoutImplicitKey", Set.of(CUSTOMER_TABLE),
                "VacationDestinationRecord customersKey",
                "this.customersKey = vacation_destination_pkey"
        );
    }

    @Test
    @DisplayName("Reference via tables should keep fields for first key")
    void referenceViaTable() {
        assertGeneratedContentContains("referenceViaTable",
                "Inventory(StoreRecord inventory_store_id_fkey)",
                "this.staffForInventoryKey = inventory_store_id_fkey"
        );
    }

    @Test
    @DisplayName("Key fields should be reused when multiple splitQuery fields use the same key")
    void multipleFieldsWithSameKey() {
        assertGeneratedContentContains("multipleFieldsWithSameKey",
                "Inventory(StoreRecord inventory_store_id_fkey)",
                "this.store1Key = inventory_store_id_fkey",
                "this.store2Key = inventory_store_id_fkey"
        );
    }

    @Test
    @DisplayName("Subtype with splitQuery reference should store foreign key fields")
    void referenceInSubtype() {
        assertGeneratedContentContains("referenceInSubtype",
                "SomeType(AddressRecord customer_address_id_fkey)"
        );
    }

    @Test
    @DisplayName("Field with self reference should store foreign key fields")
    void selfReference() {
        assertGeneratedContentContains("selfReference",
                "FilmRecord sequelKey",
                "this.sequel_fkey = sequel_fkey; this.sequelKey = sequel_fkey;"
        );
    }

    @Test
    @DisplayName("Single table interface with splitQuery field")
    void interfaceWithTable() {
        assertGeneratedContentContains("interfaceWithTable",
                "VacationRecord getSomeStringKey()"
        );
    }

    @Test
    @DisplayName("Single table interface with condition path stores primary key fields")
    void conditionPathFromInterfaceWithTable() {
        assertGeneratedContentContains("conditionPathFromInterfaceWithTable",
                "VacationDestinationRecord getVacation_destination_pkey()",
                "VacationDestinationRecord getSomeStringKey()"
        );
    }

    @Test
    @DisplayName("Multitable interface should skip splitQuery field")
    void multiTableInterface() {
        assertGeneratedContentContains("multiTableInterface",
                "interface SomeInterface { }"
        );
    }

    @Test
    @DisplayName("Multitable union should skip splitQuery field")
    void multiTableUnion() {
        assertGeneratedContentContains("multiTableUnion",
                "interface CustomerUnion { }"
        );
    }

    @Test
    @DisplayName("Scalar field from same table with splitQuery should throw error")
    void scalarSameTable() {
        assertThatThrownBy(() -> generateFiles("scalarSameTable"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Cannot resolve reference for scalar field 'Vacation.someString'.");
    }

    @Test
    @DisplayName("Subtype field with splitQuery should throw error")
    void subtype() {
        assertThatThrownBy(() -> generateFiles("subtype"))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("Cannot find implicit key for field 'VacationDestination.someType'.");
    }

    @Test
    @DisplayName("Field referencing single table interface")
    void referencingSingleTableInterface() {
        assertGeneratedContentContains("referencingSingleTableInterface", Set.of(ADDRESS_BY_DISTRICT),
                "City(AddressRecord address_city_id_fkey)",
                "this.addressesKey = address_city_id_fkey"
        );
    }

    @Test
    @DisplayName("Field referencing single table interface connection")
    void referencingSingleTableInterfaceConnection() {
        assertGeneratedContentContains("referencingSingleTableInterfaceConnection", Set.of(ADDRESS_BY_DISTRICT, ADDRESS_BY_DISTRICT_CONNECTION),
                "City(AddressRecord address_city_id_fkey)",
                "this.addressesKey = address_city_id_fkey"
        );
    }

    @Test
    @DisplayName("Field referencing multitable interface")
    void referencingMultitableInterface() {
        assertGeneratedContentContains("referencingMultitableInterface",
                "FilmCategory(FilmCategoryRecord film_category_pkey)",
                "this.titledFilmsKey = film_category_pkey"
        );
    }

    @Test
    @DisplayName("Field referencing multitable interface connection")
    void referencingMultitableInterfaceConnection() {
        assertGeneratedContentContains("referencingMultitableInterfaceConnection",
                "this.titledFilmsKey = film_category_pkey"
        );
    }

    @Test
    @DisplayName("Field referencing multitable union")
    void referencingMultitableUnion() {
        assertGeneratedContentContains("referencingMultitableUnion",
                "this.filmsKey = film_category_pkey"
        );
    }

    @Test
    @DisplayName("Field referencing multitable union connection")
    void referencingMultitableUnionConnection() {
        assertGeneratedContentContains("referencingMultitableUnionConnection",
                "this.filmsKey = film_category_pkey"
        );
    }

    @Test
    @DisplayName("Multiple keys in java record")
    void multipleKeysInJavaRecord() {
        assertGeneratedContentContains("multipleKeysInJavaRecord",
                "private AddressRecord addressKey; private CityRecord cityKey;"
        );
    }

    @Test
    @DisplayName("Listed resolver keys for java record")
    void listedKeyInJavaRecord() {
        assertGeneratedContentContains("listedKeyInJavaRecord",
                "private List<AddressRecord> addressKey;",
                "setAddressKey(List<AddressRecord> addressKey) { this.addressKey = addressKey;"
        );
    }


    @Test
    @DisplayName("Listed resolver keys for java record with split query")
    void listedKeyInJavaRecordSplitQuery() {
        assertGeneratedContentContains("listedKeyInJavaRecordSplitQuery",
                "this.addressKey = List.of(address_pkey);",
                "private List<AddressRecord> addressKey;",
                "setAddressKey(List<AddressRecord> addressKey) { this.addressKey = addressKey;"
        );
    }

    @Test
    @DisplayName("Listed resolver keys for java record with nested split query")
    void listedKeyInJavaRecordSplitQueryNested() {
        assertGeneratedContentContains("listedKeyInJavaRecordNestedSplitQuery",
                "public Payload(Result result) {this.result = result;}",
                "this.addressKey = List.of(address_pkey);",
                "private List<AddressRecord> addressKey;",
                "setAddressKey(List<AddressRecord> addressKey) { this.addressKey = addressKey;"
        );
    }
}
