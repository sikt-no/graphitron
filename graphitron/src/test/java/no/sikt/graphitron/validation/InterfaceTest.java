package no.sikt.graphitron.validation;

import no.sikt.graphitron.common.configuration.SchemaComponent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Interface validation - Checks run when building the schema for interfaces")
public class InterfaceTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "interface";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE, CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Interface is used as type for a split query")
    void splitQuery() {
        assertErrorsContain("splitQuery", "interface (Node) returned in non root object. This is not fully supported. Use with care");
    }

    @Test
    @DisplayName("Interface query taking too many arguments")
    void tooManyArguments() {
        assertErrorsContain("tooManyArguments", "Only exactly one input field is currently supported for fields returning interfaces. 'node' has 2 input fields");
    }

    @Test
    @DisplayName("Interface query taking too few arguments")
    void tooFewArguments() {
        assertErrorsContain("tooFewArguments", "Only exactly one input field is currently supported for fields returning interfaces. 'node' has 0 input fields");
    }

    @Test
    @DisplayName("Interface query returning a list")
    void listedNode() {
        assertErrorsContain("listedNode", "Generating fields returning a list of 'Node' is not supported. 'nodes' must return only one Node");
    }

    @Test
    @DisplayName("Multiple types implementing Node interface and referring to the same table will throw an exception")
    void allTypesUsingNodeInterface() {
        assertErrorsContain("allTypesUsingNodeInterface",
                            "Problems have been found that prevent code generation:\n" +
                            "Multiple types (FilmB, FilmA) implement the Node interface and refer to the same table FILM. This is not supported."
                            );
    }

    @Test
    @DisplayName("Multiple types referring to the same table where only one of these are implementing Node interface are supported")
    void notAllTypesUsingNodeInterface() {
        assertDoesNotThrow(() -> generateFiles("notAllTypesUsingNodeInterface"));
    }

    @Test
    @DisplayName("Discriminate directive on interface but not table")
    void discriminateOnInterfaceButNotTable() {
        assertErrorsContain("singleTableInterface/singleTableInterfaceWithoutTable",
                "'discriminate' and 'table' directives on interfaces must be used together. " +
                        "Interface 'Address' is missing 'table' directive.");
    }

    @Test
    @DisplayName("Table directive on interface but not discriminate")
    void tableOnInterfaceButNotDiscriminate() {
        assertErrorsContain("singleTableInterface/singleTableInterfaceWithoutDiscriminate",
                "'discriminate' and 'table' directives on interfaces must be used together. " +
                        "Interface 'Address' is missing 'discriminate' directive.");
    }

    @Test
    @DisplayName("Column in discriminate directive does not exists in table")
    void discriminateFieldOnInterfaceDoesNotExist() {
        assertErrorsContain("singleTableInterface/discriminateFieldOnInterfaceDoesNotExist",
                "Interface 'AddressByDistrict' has discriminating field set as 'COLUMN_DOES_NOT_EXIST', but the field does not exist in table 'ADDRESS'.");
    }

    @Test
    @DisplayName("Discriminating coloumn is not of a String type")
    void discriminateFieldOnInterfaceIsNotString() {
        assertErrorsContain("singleTableInterface/discriminateFieldOnInterfaceIsNotString",
                "Interface 'AddressByDistrict' has discriminating field set as 'CITY_ID', but the field does not return a string type, which is not supported.");
    }

    @Test
    @DisplayName("Discriminator directive on type, but does not implement any interfaces")
    void typeWithDiscriminatorDoesNotImplementAnyInterface() {
        assertErrorsContain("singleTableInterface/typeWithDiscriminatorDoesNotImplementAnyInterface",
                "Type 'Address' has discriminator, but doesn't implement any interfaces requiring it.");
    }

    @Test
    @DisplayName("Discriminator directive on type, but does not implement any interfaces with discriminate directive")
    void typeWithDiscriminatorDoesNotImplementSingleTableInterface() {
        assertErrorsContain("singleTableInterface/typeWithDiscriminatorDoesNotImplementSingleTableInterface",
                "Type 'AddressInDistrictOne' has discriminator, but doesn't implement any interfaces requiring it.");
    }

    @Test
    @DisplayName("Type implements single table interface but is missing discriminator directive")
    void typeWithoutDiscriminatorImplementsSingleTableInterface() {
        assertErrorsContain("singleTableInterface/typeWithoutDiscriminatorImplementsSingleTableInterface",
                "Type 'Address' is missing 'discriminator' directive in order to implement interface 'AddressByDistrict'.");
    }

    @Test
    @DisplayName("Single table interface has implementing type with different table")
    void singleTableInterfaceHasTypeWithDifferentTable() {
        assertErrorsContain("singleTableInterface/singleTableInterfaceHasTypeWithDifferentTable",
                "Interface 'AddressByDistrict' requires implementing types to have table 'ADDRESS', but type 'Address' has table 'SOME_OTHER_TABLE'.");
    }

    @Test
    @DisplayName("Mismatch in field directive on field in single table interface")
    void fieldConflict() {
        assertErrorsContain("singleTableInterface/fieldConflict",
                "Overriding 'field' configuration in types implementing a single table interface is not " +
                        "currently supported, and must be identical with interface. Type 'AddressInDistrictOne' has a configuration mismatch on field " +
                        "'postalCode' from the interface 'Address'.",
                "Type 'AddressInDistrictTwo' has a configuration mismatch"
        );
    }

    @Test
    @DisplayName("Mismatch in reference directive on field in single table interface")
    void referenceConflict() {
        assertErrorsContain("singleTableInterface/referenceConflict",
                "Overriding 'reference' configuration in types implementing a single table interface is not " +
                        "currently supported, and must be identical with interface. Type 'AddressInDistrictOne' has a configuration mismatch on field " +
                        "'customer' from the interface 'Address'.",
                        "Type 'AddressInDistrictTwo' has a configuration mismatch"
        );
    }

    @Test
    @DisplayName("Mismatch in field directive on field in single table interface")
    void conditionConflict() {
        assertErrorsContain("singleTableInterface/conditionConflict",
                "Overriding 'condition' configuration in types implementing a single table interface is not " +
                        "currently supported, and must be identical with interface. Type 'AddressInDistrictOne' has a configuration mismatch on field " +
                        "'customer' from the interface 'Address'.",
                "Type 'AddressInDistrictTwo' has a configuration mismatch"
        );
    }

    @Test
    @DisplayName("Mismatch in field directive on field in type implementing single table interface")
    void fieldInTypeConflict() {
        assertErrorsContain("singleTableInterface/fieldInTypeConflict",
                "Different configuration on fields in types implementing the same single table interface is currently not supported. Field " +
                        "'sharedField' occurs in two or more types implementing interface 'Address', but there is a mismatch between the configuration of the 'field' directive.");
    }

    @Test
    @DisplayName("Mismatch in reference directive on field in type implementing single table interface")
    void referenceInTypeConflict() {
        assertErrorsContain("singleTableInterface/referenceInTypeConflict",
                "Different configuration on fields in types implementing the same single table interface is currently not supported. " +
                        "Field 'customer' occurs in two or more types implementing interface 'Address', but there is a mismatch between the configuration of the 'reference' directive.");
    }

    @Test
    @DisplayName("Mismatch in condition reference directive on field in type implementing single table interface")
    void conditionReferenceInTypeConflict() {
        assertErrorsContain("singleTableInterface/conditionReferenceInTypeConflict",
                "Different configuration on fields in types implementing the same single table interface is currently not supported. " +
                        "Field 'customer' occurs in two or more types implementing interface 'Address', but there is a mismatch between the configuration of the 'reference' directive.");
    }
}
