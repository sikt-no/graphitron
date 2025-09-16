package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class SingleTableInterfaceTest extends InterfaceTest {

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/singleTableInterface";
    }


    @Test
    @DisplayName("Default case")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Listed without pagination")
    void listed() {
        assertGeneratedContentMatches("listed");
    }

    @Test
    @DisplayName("Nested")
    void nested() {
        assertGeneratedContentMatches("nested");
    }

    @Test
    @DisplayName("Paginated")
    void paginated() {
        assertGeneratedContentMatches("paginated");
    }

    @Test
    @DisplayName("With reference field from interface definition")
    void withReferenceField() {
        assertGeneratedContentContains("withReferenceField",
                Set.of(CUSTOMER_TABLE),
                "address_2030472956_customer = _address.customer()",
                "DSL.field(",
                ".from(address_2030472956_customer)).as(\"customer\")");
    }

    @Test
    @DisplayName("With condition reference field")
    void withConditionReferenceField() {
        assertGeneratedContentContains("withConditionReferenceField",
                Set.of(CUSTOMER_TABLE),
                ".join(address_customer_addresscustomer_customer).on(no.",
                "addressCustomer(address_customer, address_customer_addresscustomer_customer)"
        );
    }

    @Test
    @DisplayName("With reference field from type definition")
    void withReferenceFieldInType() {
        assertGeneratedContentContains("withReferenceFieldInType",
                Set.of(CUSTOMER_TABLE),
                "address_2030472956_customer = _address.customer()",
                "DSL.field(",
                ".from(address_2030472956_customer)).as(\"customer\")"
        );
    }

    @Test
    @DisplayName("Implementing types have the same field")
    void implementingTypesWithSameField() {
        assertGeneratedContentContains("implementingTypesWithSameField",
                Set.of(CUSTOMER_TABLE),
                // Check all selected fields to make sure the duplicate field is only selected once
                "(_address.DISTRICT.as(\"_discriminator\"),_address.POSTAL_CODE.as(\"postalCode\")" +
                        ",_address.PHONE.as(\"phoneNumber\"))");
    }

    @Test
    @Disabled("synthetic colums are not supported in jOOQ open source. Fix in GGG-159")
    @DisplayName("Discriminator column name is case insensitive")
    void discriminatorColumnNameIsCaseInsensitive() {
        assertGeneratedContentMatches("discriminatorColumnNameIsCaseInsensitive");
    }

    @Test
    @DisplayName("Type implementing multiple single table interfaces")
    void typeImplementsMultipleSingleTableInterfaces() {
        assertDoesNotThrow(() -> generateFiles("typeImplementsMultipleSingleTableInterfaces"));
    }

    @Test
    @DisplayName("Overridden field")
    void overriddenField() {
        assertGeneratedContentContains("overriddenField",
                "_discriminator\"), _address.POSTAL_CODE.as(\"postalCode\"), _address.ADDRESS_.as(\"ONE_postalCode\"))",
                "_data.setPostalCode(internal_it_.get(\"ONE_postalCode\", _address.ADDRESS_.getConverter()));",
                "{return internal_it_.into(AddressInDistrictTwo.class);}"

        );
    }

    @Test
    @DisplayName("Default field should not be selected when overridden by all types")
    void interfaceFieldOverriddenByAll() {
        assertGeneratedContentContains("interfaceFieldOverriddenByAll",
                "_discriminator\"), _address.ADDRESS_.as(\"ONE_postalCode\"), _address.ADDRESS2.as(\"TWO_postalCode\"))"

        );
    }

    @Test
    @DisplayName("Overridden non-interface field")
    void overriddenNonInterfaceField() {
        assertGeneratedContentContains("overriddenNonInterfaceField",
                "_address.ADDRESS_.as(\"ONE_extraField\"), _address.POSTAL_CODE.as(\"TWO_extraField\")",
                ".into(AddressInDistrictOne.class); _data.setExtraField(internal_it_.get(\"ONE_extraField\", _address.ADDRESS_.getConverter",
                ".into(AddressInDistrictTwo.class); _data.setExtraField(internal_it_.get(\"TWO_extraField\", _address.POSTAL_CODE.getConverter"

        );
    }

    @Test
    @DisplayName("With splitQuery reference in interface")
    void splitQueryReferenceInInterface() {
        assertGeneratedContentContains("splitQueryReferenceInInterface",
                "DSL.row(_address.ADDRESS_ID).as(\"address_pkey\")",
                " _data.setCustomerKey(internal_it_.get(\"address_pkey\", Record1.class).valuesRow())"

        );
    }

    @Test
    @DisplayName("With splitQuery reference in type")
    void splitQueryReferenceInType() {
        assertGeneratedContentContains("splitQueryReferenceInType",
                "DSL.row(_address.ADDRESS_ID).as(\"address_pkey\")",
                "AddressInDistrictOne.class); _data.setCustomerKey(internal_it_.get(\"address_pkey\"",
                "return internal_it_.into(AddressInDistrictTwo.class);"

        );
    }
}
