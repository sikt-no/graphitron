package no.sikt.graphitron.validation;

import no.sikt.graphitron.common.configuration.SchemaComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Single table interface validation")
public class SingleTableInterfaceTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "interface/singleTable";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Discriminate directive on interface but not table")
    void missingTable() {
        assertErrorsContain("missingTable",
                "'discriminate' and 'table' directives on interfaces must be used together. " +
                        "Interface 'Address' is missing 'table' directive.");
    }

    @Test
    @DisplayName("Table directive on interface but not discriminate")
    void missingDiscriminate() {
        assertErrorsContain("missingDiscriminate",
                "'discriminate' and 'table' directives on interfaces must be used together. " +
                        "Interface 'Address' is missing 'discriminate' directive.");
    }

    @Test
    @DisplayName("Column in discriminate directive does not exists in table")
    void discriminateFieldDoesNotExist() {
        assertErrorsContain("discriminateFieldDoesNotExist",
                "Interface 'AddressByDistrict' has discriminating field set as 'COLUMN_DOES_NOT_EXIST', but the field does not exist in table 'ADDRESS'.");
    }

    @Test
    @DisplayName("Discriminating column is not of a String type")
    void discriminateFieldIsNotString() {
        assertErrorsContain("discriminateFieldIsNotString",
                "Interface 'AddressByDistrict' has discriminating field set as 'CITY_ID', but the field does not return a string type, which is not supported.");
    }

    @Test
    @DisplayName("Discriminator directive on type, but does not implement any interfaces")
    void typeWithDiscriminatorDoesNotImplementAnyInterface() {
        assertErrorsContain("typeWithDiscriminatorDoesNotImplementAnyInterface",
                "Type 'Address' has discriminator, but doesn't implement any interfaces requiring it.");
    }

    @Test
    @DisplayName("Discriminator directive on type, but does not implement any interfaces with discriminate directive")
    void typeWithDiscriminatorDoesNotImplementSingleTableInterface() {
        assertErrorsContain("typeWithDiscriminatorDoesNotImplementSingleTableInterface",
                "Type 'AddressInDistrictOne' has discriminator, but doesn't implement any interfaces requiring it.");
    }

    @Test
    @DisplayName("Type implements single table interface but is missing discriminator directive")
    void typeWithoutDiscriminatorImplementsSingleTableInterface() {
        assertErrorsContain("typeWithoutDiscriminatorImplementsSingleTableInterface",
                "Type 'Address' is missing 'discriminator' directive in order to implement interface 'AddressByDistrict'.");
    }

    @Test
    @DisplayName("Single table interface has implementing type with different table")
    void singleTableInterfaceHasTypeWithDifferentTable() {
        assertErrorsContain("singleTableInterfaceHasTypeWithDifferentTable",
                "Interface 'AddressByDistrict' requires implementing types to have table 'ADDRESS', but type 'Address' has table 'SOME_OTHER_TABLE'.");
    }

    @Test
    @DisplayName("Mismatch in field directive on field in single table interface")
    void fieldConflict() {
        assertErrorsContain("fieldConflict",
                "Overriding 'field' configuration in types implementing a single table interface is not " +
                        "currently supported, and must be identical with interface. Type 'AddressInDistrictOne' has a configuration mismatch on field " +
                        "'postalCode' from the interface 'Address'.",
                "Type 'AddressInDistrictTwo' has a configuration mismatch"
        );
    }

    @Test
    @DisplayName("Mismatch in reference directive on field in single table interface")
    void referenceConflict() {
        assertErrorsContain("referenceConflict",
                "Overriding 'reference' configuration in types implementing a single table interface is not " +
                        "currently supported, and must be identical with interface. Type 'AddressInDistrictOne' has a configuration mismatch on field " +
                        "'customer' from the interface 'Address'.",
                "Type 'AddressInDistrictTwo' has a configuration mismatch"
        );
    }

    @Test
    @DisplayName("Mismatch in field directive on field in single table interface")
    void conditionConflict() {
        assertErrorsContain("conditionConflict",
                "Overriding 'condition' configuration in types implementing a single table interface is not " +
                        "currently supported, and must be identical with interface. Type 'AddressInDistrictOne' has a configuration mismatch on field " +
                        "'customer' from the interface 'Address'.",
                "Type 'AddressInDistrictTwo' has a configuration mismatch"
        );
    }

    @Test
    @DisplayName("Mismatch in field directive on field in type implementing single table interface")
    void fieldInTypeConflict() {
        assertErrorsContain("fieldInTypeConflict",
                "Different configuration on fields in types implementing the same single table interface is currently not supported. Field " +
                        "'sharedField' occurs in two or more types implementing interface 'Address', but there is a mismatch between the configuration of the 'field' directive.");
    }

    @Test
    @DisplayName("Matching reference directive on field in type implementing single table interface")
    void referenceMatches() {
        assertDoesNotThrow(() -> generateFiles("referenceMatches"));
    }

    @Test
    @DisplayName("Mismatch in reference directive on field in type implementing single table interface")
    void referenceInTypeConflict() {
        assertErrorsContain("referenceInTypeConflict",
                "Different configuration on fields in types implementing the same single table interface is currently not supported. " +
                        "Field 'customer' occurs in two or more types implementing interface 'Address', but there is a mismatch between the configuration of the 'reference' directive.");
    }

    @Test
    @DisplayName("Mismatch in condition reference directive on field in type implementing single table interface")
    void conditionReferenceInTypeConflict() {
        assertErrorsContain("conditionReferenceInTypeConflict",
                "Different configuration on fields in types implementing the same single table interface is currently not supported. " +
                        "Field 'customer' occurs in two or more types implementing interface 'Address', but there is a mismatch between the configuration of the 'reference' directive.");
    }

    @Test
    @DisplayName("Single table interface is used as type for a split query")
    void splitQuery() {
        assertErrorsContain("splitQuery", "interface (AddressInterface) returned in non root object. This is not fully supported. ");
    }
}
