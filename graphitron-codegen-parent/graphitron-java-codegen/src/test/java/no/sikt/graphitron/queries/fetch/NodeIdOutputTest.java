package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;

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
}
