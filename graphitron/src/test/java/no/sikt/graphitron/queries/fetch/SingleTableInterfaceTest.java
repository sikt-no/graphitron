package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

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
    @DisplayName("Discriminator column name is case insensitive")
    void discriminatorColumnNameIsCaseInsensitive() {
        assertGeneratedContentMatches("discriminatorColumnNameIsCaseInsensitive");
    }
}
