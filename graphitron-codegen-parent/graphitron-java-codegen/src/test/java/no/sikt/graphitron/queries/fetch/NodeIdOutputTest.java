package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Node ID output - fields with nodeId directive as output")
public class NodeIdOutputTest extends NodeIdDirectiveTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "output";
    }

    @Test
    @DisplayName("Default case")
    void defaultCase() {
        assertGeneratedContentContains("default", Set.of(CUSTOMER_QUERY),
                "row(nodeIdStrategy.createId(\"Customer\", _customer.fields(_customer.getPrimaryKey().getFieldsArray()))).mapping"
        );
    }

    @Test
    @DisplayName("With reference")
    void reference() {
        assertGeneratedContentContains("reference", Set.of(CUSTOMER_QUERY),
                "field(DSL.select(nodeIdStrategy.createId(\"Address\", customer_2952383337_address.fields"
        );
    }

    @Test
    @DisplayName("With reference and where statement")
    void referenceAndInput() {
        assertGeneratedContentContains("referenceAndInput", Set.of(CUSTOMER_NODE),
                "_customer.getPrimaryKey().getFieldsArray()))).from(_customer).where(_customer.FIRST_NAME.eq(inRecord.getFirstName()))",
                "CustomerNoTable::new))).fetchOne("
        );
    }

    @Test
    @DisplayName("With reference via table")
    void referenceViaTable() {
        assertGeneratedContentContains("referenceViaTable", Set.of(CUSTOMER_QUERY),
                "field(DSL.select(nodeIdStrategy.createId(\"City\", address_1214171484_city.fields"
        );
    }

    @Test
    @DisplayName("With implicit reference from typeName in @nodeId")
    void implicitReference() {
        assertGeneratedContentContains("implicitReference", Set.of(CUSTOMER_QUERY),
                "field(DSL.select(nodeIdStrategy.createId(\"Address\", customer_2952383337_address.fields"
        );
    }

    @Test
    @DisplayName("With condition reference")
    void conditionReference() {
        assertGeneratedContentContains("conditionReference", Set.of(CUSTOMER_QUERY),
                ".from(customer_addressid).join(customer_addressid_addressid_address).on(no.",
                "createId(\"Address\", customer_addressid_addressid_address.fields"
        );
    }

    @Test
    @DisplayName("With condition reference and key")
    void conditionReferenceWithKey() {
        assertGeneratedContentContains("conditionReferenceWithKey", Set.of(CUSTOMER_QUERY),
                "createId(\"Address\", customer_2952383337_address.fields"
        );
    }

    @Test
    @DisplayName("With self-reference")
    void selfReference() {
        assertGeneratedContentContains("selfReference",
                "createId(\"Film\", film_3747728953_film.fields"
        );
    }

    @Test
    @DisplayName("Returning ID without type wrapping")
    void noWrapping() {
        assertGeneratedContentContains(
                "noWrapping", Set.of(CUSTOMER_NODE_INPUT_TABLE, CUSTOMER_NODE),
                "String queryForQuery(",
                ".select(nodeIdStrategy.createId(\"CustomerNode\"",
                ".where(nodeIdStrategy.hasId(\"CustomerNode\"",
                ".fetchOne(it -> it.into(String.class));"
        );
    }

    @Test
    @DisplayName("Node IDs in single table interface queries")
    void inSingleTableInterfaceQuery() {
        assertGeneratedContentContains("singleTableInterface",
                "nodeIdStrategy.createId(\"A1\", _address.ADDRESS_ID).as(\"ONE_id\")",
                "nodeIdStrategy.createId(\"A2\", _address.fields(_address.getPrimaryKey().getFieldsArray())).as(\"TWO_id\")",
                "var _data = internal_it_.into(AddressInDistrictOne.class);" +
                        "_data.setId(internal_it_.get(\"ONE_id\", String.class));" +
                        "return _data;",
                "into(AddressInDistrictTwo.class); _data.setId(internal_it_.get(\"TWO_id\", String.class));"
        );
    }
}
