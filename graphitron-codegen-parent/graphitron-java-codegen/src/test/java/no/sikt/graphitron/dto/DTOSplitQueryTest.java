package no.sikt.graphitron.dto;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.dto.InterfaceDTOGenerator;
import no.sikt.graphitron.generators.dto.TypeDTOGenerator;
import no.sikt.graphitron.generators.dto.UnionDTOGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DTOSplitQueryTest extends DTOGeneratorTest {
    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new TypeDTOGenerator(schema), new InterfaceDTOGenerator(schema), new UnionDTOGenerator(schema));
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "splitQuery";
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
                "Record1<Long> vacationKey",
                "VacationDestination(Record1<Long> vacation_destination_vacation_fkey)",
                "this.vacationKey = vacation_destination_vacation_fkey"
        );
    }

    @Test
    @DisplayName("Listed field should keep primary key fields")
    void listedReference() {
        assertGeneratedContentContains("listedReference",
                "Record1<Long> destinationKey",
                "Vacation(Record1<Long> primaryKey)",
                "this.destinationKey = primaryKey"
        );
    }

    @Test
    @DisplayName("Fields with reverse reference should keep primary key fields even if they are not listed")
    void reverseReference() {
        assertGeneratedContentContains("reverseReference",
                "Record1<Long> destinationKey",
                "Vacation(Record1<Long> primaryKey)",
                "this.destinationKey = primaryKey"
        );
    }

    @Test
    @DisplayName("Connection field with splitQuery referencing another table should store primary key fields")
    void connectionReference() {
        assertGeneratedContentContains("connectionReference", Set.of(CUSTOMER_TABLE, CUSTOMER_CONNECTION),
                "Store(Record1<Long> primaryKey)",
                "this.customersKey = primaryKey"
        );
    }

    @Test
    @DisplayName("SplitQuery field  referencing another table with condition should store primary key fields")
    void conditionPath() {
        assertGeneratedContentContains("conditionPath",
                "VacationDestination(Record2<Long, String> primaryKey) " +
                        "{ this.primaryKey = primaryKey; this.someStringsKey = primaryKey; }",
                "private Record2<Long, String> someStringsKey"
        );
    }

    @Test
    @DisplayName("Field reference with both condition and implicit key should store foreign key fields")
    void conditionWithImplicitKey() {
        assertGeneratedContentContains("conditionWithImplicitKey",
                "this.vacationsKey = vacation_destination_vacation_fkey"
        );
    }

    @Test
    @DisplayName("Field reference with both condition and no implicit key should store primary key fields")
    void conditionWithoutImplicitKey() {
        assertGeneratedContentContains("conditionWithoutImplicitKey", Set.of(CUSTOMER_TABLE),
                "this.customersKey = primaryKey"
        );
    }

    @Test
    @DisplayName("Reference via tables should keep fields for first key")
    void referenceViaTable() {
        assertGeneratedContentContains("referenceViaTable",
                "Inventory(Record1<Long> inventory_store_id_fkey)",
                "this.staffForInventoryKey = inventory_store_id_fkey"
        );
    }

    @Test
    @DisplayName("Key fields should be reused when multiple splitQuery fields use the same key")
    void multipleFieldsWithSameKey() {
        assertGeneratedContentContains("multipleFieldsWithSameKey",
                "Inventory(Record1<Long> inventory_store_id_fkey)",
                "this.store1Key = inventory_store_id_fkey",
                "this.store2Key = inventory_store_id_fkey"
        );
    }

    @Test
    @DisplayName("Subtype with splitQuery reference should store foreign key fields")
    void referenceInSubtype() {
        assertGeneratedContentContains("referenceInSubtype",
                "SomeType(Record1<Long> customer_address_id_fkey)"
        );
    }

    @Test
    @DisplayName("Field with self reference should store foreign key fields")
    void selfReference() {
        assertGeneratedContentContains("selfReference",
                "Record1<Long> sequelKey",
                "this.sequel_fkey = sequel_fkey; this.sequelKey = sequel_fkey;"
        );
    }

    @Test
    @DisplayName("Single table interface with splitQuery field")
    void interfaceWithTable() {
        assertGeneratedContentContains("interfaceWithTable",
                "Record1<Long> getSomeStringKey()"
        );
    }

    @Test
    @DisplayName("Single table interface with splitQuery field")
    void conditionPathFromInterfaceWithTable() {
        assertGeneratedContentContains("conditionPathFromInterfaceWithTable",
                "Record2<Long, String> getPrimaryKey()",
                "Record2<Long, String> getSomeStringKey()"
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
                .hasMessage("Cannot resolve reference for scalar field 'someString' in type 'Vacation'.");
    }

    @Test
    @DisplayName("Subtype field with splitQuery should throw error")
    void subtype() {
        assertThatThrownBy(() -> generateFiles("subtype"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Cannot find implicit key for field 'someType' in type 'VacationDestination'.");
    }

    @Test
    @DisplayName("Field referencing single table interface")
    void referencingSingleTableInterface() {
        assertGeneratedContentContains("referencingSingleTableInterface", Set.of(ADDRESS_BY_DISTRICT),
                "City(Record1<Long> primaryKey)",
                "this.addressesKey = primaryKey;"
        );
    }

    @Test
    @DisplayName("Field referencing single table interface connection")
    void referencingSingleTableInterfaceConnection() {
        assertGeneratedContentContains("referencingSingleTableInterfaceConnection", Set.of(ADDRESS_BY_DISTRICT, ADDRESS_BY_DISTRICT_CONNECTION),
                "City(Record1<Long> primaryKey)",
                "this.addressesKey = primaryKey;"
        );
    }
}
