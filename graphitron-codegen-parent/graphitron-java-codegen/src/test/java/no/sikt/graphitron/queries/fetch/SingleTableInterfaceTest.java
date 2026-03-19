package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.ADDRESS_SINGLE_TABLE_INTERFACE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Single-table interfaces - Queries")
public class SingleTableInterfaceTest extends InterfaceTest {

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/singleTableInterface";
    }


    @Test
    @DisplayName("Default case")
    void defaultCase() {
        assertGeneratedContentMatches("default", ADDRESS_SINGLE_TABLE_INTERFACE);
    }

    @Test
    @DisplayName("Listed without pagination")
    void listed() {
        assertGeneratedContentMatches("listed", ADDRESS_SINGLE_TABLE_INTERFACE);
    }

    @Test
    @DisplayName("Nested")
    void nested() {
        assertGeneratedContentMatches("nested");
    }

    @Test
    @DisplayName("Paginated")
    void paginated() {
        assertGeneratedContentMatches("paginated", ADDRESS_SINGLE_TABLE_INTERFACE);
    }

    @Test
    @DisplayName("With reference field from interface definition")
    void withReferenceField() {
        assertGeneratedContentContains("withReferenceField",
                Set.of(CUSTOMER_TABLE),
                "address_223244161_customer = _a_address.customer()",
                "DSL.field(",
                ".from(_a_address_223244161_customer)).as(\"customer\")");
    }

    @Test
    @DisplayName("With condition reference field")
    void withConditionReferenceField() {
        assertGeneratedContentContains("withConditionReferenceField",
                Set.of(CUSTOMER_TABLE),
                ".join(_a_address_customer_addresscustomer_customer).on(no.",
                "addressCustomer(_a_address_customer, _a_address_customer_addresscustomer_customer)"
        );
    }

    @Test
    @DisplayName("With reference field from type definition")
    void withReferenceFieldInType() {
        assertGeneratedContentContains("withReferenceFieldInType",
                Set.of(CUSTOMER_TABLE, ADDRESS_SINGLE_TABLE_INTERFACE),
                "address_223244161_customer = _a_address.customer()",
                "DSL.field(",
                ".from(_a_address_223244161_customer)).as(\"customer\")"
        );
    }

    @Test
    @DisplayName("Implementing types have the same field")
    void implementingTypesWithSameField() {
        assertGeneratedContentContains("implementingTypesWithSameField",
                Set.of(CUSTOMER_TABLE, ADDRESS_SINGLE_TABLE_INTERFACE),
                // Check all selected fields to make sure the duplicate field is only selected once
                "(_a_address.DISTRICT.as(\"_iv_discriminator\"),_a_address.POSTAL_CODE.as(\"postalCode\")" +
                        ",_a_address.PHONE.as(\"phoneNumber\"))"
        );
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
        assertDoesNotThrow(() -> generateFiles("typeImplementsMultipleSingleTableInterfaces", Set.of(ADDRESS_SINGLE_TABLE_INTERFACE)));
    }

    @Test
    @DisplayName("Overridden field")
    void overriddenField() {
        assertGeneratedContentContains("overriddenField",
                Set.of(ADDRESS_SINGLE_TABLE_INTERFACE),
                "discriminator\"), _a_address.POSTAL_CODE.as(\"postalCode\"), _a_address.ADDRESS_.as(\"ONE_postalCode\"))",
                "data.setPostalCode(_iv_it.get(\"ONE_postalCode\", _a_address.ADDRESS_.getConverter()));",
                "{return _iv_it.into(AddressInDistrictTwo.class);}"

        );
    }

    @Test
    @DisplayName("Default field should not be selected when overridden by all types")
    void interfaceFieldOverriddenByAll() {
        assertGeneratedContentContains("interfaceFieldOverriddenByAll",
                Set.of(ADDRESS_SINGLE_TABLE_INTERFACE),
                "discriminator\"), _a_address.ADDRESS_.as(\"ONE_postalCode\"), _a_address.ADDRESS2.as(\"TWO_postalCode\"))"

        );
    }

    @Test
    @DisplayName("Overridden non-interface field")
    void overriddenNonInterfaceField() {
        assertGeneratedContentContains("overriddenNonInterfaceField",
                Set.of(ADDRESS_SINGLE_TABLE_INTERFACE),
                "address.ADDRESS_.as(\"ONE_extraField\"), _a_address.POSTAL_CODE.as(\"TWO_extraField\")",
                ".into(AddressInDistrictOne.class); _iv_data.setExtraField(_iv_it.get(\"ONE_extraField\", _a_address.ADDRESS_.getConverter",
                ".into(AddressInDistrictTwo.class); _iv_data.setExtraField(_iv_it.get(\"TWO_extraField\", _a_address.POSTAL_CODE.getConverter"

        );
    }

    @Test
    @DisplayName("Single-table interface on non-root type (singular)")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery",
                Set.of(ADDRESS_SINGLE_TABLE_INTERFACE),
                "Map<CityRecord, Address>",
                "Set<CityRecord> _rk_city",
                "DSL.row(_a_city.CITY_ID).in(_rk_city.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList())",
                "fetchMap(_iv_r -> _iv_r.get(\"_rk_city\", CityRecord.class).valuesRow()"
        );
    }

    @Test
    @DisplayName("Single-table interface on non-root type (listed)")
    void splitQueryListed() {
        assertGeneratedContentContains("splitQueryListed",
                Set.of(ADDRESS_SINGLE_TABLE_INTERFACE),
                "Map<CityRecord, List<Address>>",
                "fetchGroups("
        );
    }

    @Test
    @DisplayName("With splitQuery reference in interface")
    void splitQueryReferenceInInterface() {
        assertGeneratedContentContains("splitQueryReferenceInInterface",
                // Make sure only correct fields are selected
                """
                        select(
                            DSL.row(_a_address.ADDRESS_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_address.ADDRESS_ID))).as("address_pkey"),
                            _a_address.DISTRICT.as("_iv_discriminator"),
                            _a_address.POSTAL_CODE.as("postalCode")
                            )
                        """,
                "data.setCustomerKey(_iv_it.get(\"address_pkey\", AddressRecord.class))"
        );
    }

    @Test
    @DisplayName("With splitQuery reference in type")
    void splitQueryReferenceInType() {
        assertGeneratedContentContains("splitQueryReferenceInType",
                Set.of(ADDRESS_SINGLE_TABLE_INTERFACE),
                "DSL.row(_a_address.ADDRESS_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_address.ADDRESS_ID))).as(\"address_pkey\")",
                "AddressInDistrictOne.class); _iv_data.setCustomerKey(_iv_it.get(\"address_pkey\"",
                "return _iv_it.into(AddressInDistrictTwo.class);"
        );
    }
}
