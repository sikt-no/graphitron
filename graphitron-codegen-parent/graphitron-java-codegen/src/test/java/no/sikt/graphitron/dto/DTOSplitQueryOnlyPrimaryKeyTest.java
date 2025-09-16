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

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_CONNECTION;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Temporary test class for only using primary key in splitQuery fields")
/*
* This class is mostly a copy of DTOSplitQueryTest, but only keeping primary keys.
* The option to only use primary keys for the generator is temporary and will be removed.
* */
public class DTOSplitQueryOnlyPrimaryKeyTest extends DTOGeneratorTest {
    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new TypeDTOGenerator(schema), new InterfaceDTOGenerator(schema), new UnionDTOGenerator(schema));
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "splitQuery";
    }

    @Test
    @DisplayName("Scalar field with splitQuery referencing another table should store primary key fields")
    void scalarReference() {
        assertGeneratedContentContains("scalarReference",
                "VacationDestination(Record2<Long, String> vacation_destination_pkey)",
                "this.vacationDescriptionKey = vacation_destination_pkey"
                );
    }

    @Test
    @DisplayName("Field with splitQuery referencing another table should store primary key fields")
    void reference() {
        assertGeneratedContentContains("reference",
                "Row2<Long, String> vacation_destination_pkey",
                "VacationDestination(Record2<Long, String> vacation_destination_pkey)",
                "this.vacationKey = vacation_destination_pkey"
        );
    }

    @Test
    @DisplayName("Listed field should keep primary key fields")
    void listedReference() {
        assertGeneratedContentContains("listedReference",
                "Row1<Long> destinationKey",
                "Vacation(Record1<Long> vacation_pkey)",
                "this.destinationKey = vacation_pkey"
        );
    }

    @Test
    @DisplayName("Fields with reverse reference should keep primary key fields even if they are not listed")
    void reverseReference() {
        assertGeneratedContentContains("reverseReference",
                "Row1<Long> destinationKey",
                "Vacation(Record1<Long> vacation_pkey)",
                "this.destinationKey = vacation_pkey"
        );
    }

    @Test
    @DisplayName("Connection field with splitQuery referencing another table should store primary key fields")
    void connectionReference() {
        assertGeneratedContentContains("connectionReference", Set.of(CUSTOMER_TABLE, CUSTOMER_CONNECTION),
                "Store(Record1<Long> store_pkey)",
                "this.customersKey = store_pkey"
        );
    }

    @Test
    @DisplayName("SplitQuery field  referencing another table with condition should store primary key fields")
    void conditionPath() {
        assertGeneratedContentContains("conditionPath",
                "VacationDestination(Record2<Long, String> vacation_destination_pkey) " +
                        "{ this.vacation_destination_pkey = vacation_destination_pkey.valuesRow(); this.someStringsKey = vacation_destination_pkey.valuesRow(); }",
                "private Row2<Long, String> someStringsKey"
        );
    }

    @Test
    @DisplayName("Field reference with both condition and implicit key should store primary key fields")
    void conditionWithImplicitKey() {
        assertGeneratedContentContains("conditionWithImplicitKey",
                "this.vacationsKey = vacation_destination_pkey"
        );
    }

    @Test
    @DisplayName("Field reference with both condition and no implicit key should store primary key fields")
    void conditionWithoutImplicitKey() {
        assertGeneratedContentContains("conditionWithoutImplicitKey", Set.of(CUSTOMER_TABLE),
                "this.customersKey = vacation_destination_pkey"
        );
    }

    @Test
    @DisplayName("Reference via tables should keep fields for first key")
    void referenceViaTable() {
        assertGeneratedContentContains("referenceViaTable",
                "Inventory(Record1<Long> inventory_pkey)",
                "this.staffForInventoryKey = inventory_pkey"
        );
    }

    @Test
    @DisplayName("Key fields should be reused when multiple splitQuery fields use the same key")
    void multipleFieldsWithSameKey() {
        assertGeneratedContentContains("multipleFieldsWithSameKey",
                "Inventory(Record1<Long> inventory_pkey)",
                "this.store1Key = inventory_pkey",
                "this.store2Key = inventory_pkey"
        );
    }

    @Test
    @DisplayName("Subtype with splitQuery reference should store primary key fields")
    void referenceInSubtype() {
        assertGeneratedContentContains("referenceInSubtype",
                "SomeType(Record1<Long> customer_pkey)"
        );
    }

    @Test
    @DisplayName("Field with self reference should store primary key fields")
    void selfReference() {
        assertGeneratedContentContains("selfReference",
                "Row1<Long> sequelKey",
                "this.sequelKey = film_pkey"
        );
    }

    @Test
    @DisplayName("Single table interface with splitQuery field")
    void interfaceWithTable() {
        assertGeneratedContentContains("interfaceWithTable",
                "Row2<Long, String> getSomeStringKey()"
        );
    }

    @Test
    @DisplayName("Single table interface with splitQuery field")
    void conditionPathFromInterfaceWithTable() {
        assertGeneratedContentContains("conditionPathFromInterfaceWithTable",
                "Row2<Long, String> getVacation_destination_pkey()",
                "Row2<Long, String> getSomeStringKey()"
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
}
