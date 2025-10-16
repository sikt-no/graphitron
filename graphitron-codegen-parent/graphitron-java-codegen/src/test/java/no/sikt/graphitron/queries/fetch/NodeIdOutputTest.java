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
                "row(_iv_nodeIdStrategy.createId(\"Customer\", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))).mapping"
        );
    }

    @Test
    @DisplayName("With reference")
    void reference() {
        assertGeneratedContentContains("reference", Set.of(CUSTOMER_QUERY),
                "field(DSL.select(_iv_nodeIdStrategy.createId(\"Address\", _a_customer_2168032777_address.fields"
        );
    }

    @Test
    @DisplayName("With reference and where statement")
    void referenceAndInput() {
        assertGeneratedContentContains("referenceAndInput", Set.of(CUSTOMER_NODE),
                "customer.getPrimaryKey().getFieldsArray()))).from(_a_customer).where(_a_customer.FIRST_NAME.eq(inRecord.getFirstName()))",
                "queryForQuery_customerNoTable(inRecord, _iv_nodeIdStrategy)).fetchOne("
        );
    }

    @Test
    @DisplayName("With reference via table")
    void referenceViaTable() {
        assertGeneratedContentContains("referenceViaTable", Set.of(CUSTOMER_QUERY),
                "field(DSL.select(_iv_nodeIdStrategy.createId(\"City\", _a_address_2138977089_city.fields"
        );
    }

    @Test
    @DisplayName("With implicit reference from typeName in @nodeId")
    void implicitReference() {
        assertGeneratedContentContains("implicitReference", Set.of(CUSTOMER_QUERY),
                "field(DSL.select(_iv_nodeIdStrategy.createId(\"Address\", _a_customer_2168032777_address.fields"
        );
    }

    @Test
    @DisplayName("With condition reference")
    void conditionReference() {
        assertGeneratedContentContains("conditionReference", Set.of(CUSTOMER_QUERY),
                ".from(_a_customer_addressid).join(_a_customer_addressid_addressid_address).on(no.",
                "createId(\"Address\", _a_customer_addressid_addressid_address.fields"
        );
    }

    @Test
    @DisplayName("With condition reference and key")
    void conditionReferenceWithKey() {
        assertGeneratedContentContains("conditionReferenceWithKey", Set.of(CUSTOMER_QUERY),
                "createId(\"Address\", _a_customer_2168032777_address.fields"
        );
    }

    @Test
    @DisplayName("With self-reference")
    void selfReference() {
        assertGeneratedContentContains("selfReference",
                "createId(\"Film\", _a_film_2185543202_film.fields"
        );
    }

    @Test
    @DisplayName("Returning ID without type wrapping")
    void noWrapping() {
        assertGeneratedContentContains(
                "noWrapping", Set.of(CUSTOMER_NODE_INPUT_TABLE, CUSTOMER_NODE),
                "String queryForQuery(",
                ".select(_iv_nodeIdStrategy.createId(\"CustomerNode\"",
                ".where(_iv_nodeIdStrategy.hasId(\"CustomerNode\"",
                ".fetchOne(_iv_it -> _iv_it.into(String.class));"
        );
    }

    @Test
    @DisplayName("Node IDs in single table interface queries")
    void inSingleTableInterfaceQuery() {
        assertGeneratedContentContains("singleTableInterface",
                "nodeIdStrategy.createId(\"A1\", _a_address.ADDRESS_ID).as(\"ONE_id\")",
                "nodeIdStrategy.createId(\"A2\", _a_address.fields(_a_address.getPrimaryKey().getFieldsArray())).as(\"TWO_id\")",
                "data = _iv_it.into(AddressInDistrictOne.class);" +
                        "_iv_data.setId(_iv_it.get(\"ONE_id\", String.class));" +
                        "return _iv_data;",
                "into(AddressInDistrictTwo.class); _iv_data.setId(_iv_it.get(\"TWO_id\", String.class));"
        );
    }

    @Test
    @DisplayName("Node ID in another table object with same table as node type")
    void inAnotherTableObjectWithSameTable() {
        assertGeneratedContentContains("inAnotherTableObjectWithSameTable",
                "return DSL.row(_iv_nodeIdStrategy.createId(\"C\", _a_customer" // Don't select as a reference
        );
    }
}
