package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_RECORD;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;

@DisplayName("Node ID input - input fields with nodeId directive")
public class NodeIdInputTest extends NodeIdDirectiveTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "input";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_RECORD);
    }

    @Test
    @DisplayName("Default case")
    void defaultCase() {
        assertGeneratedContentContains("default",
                ".where(nodeIdStrategy.hasId(\"CUSTOMER\", customerId, _customer.fields(_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("With reference")
    void reference() {
        assertGeneratedContentContains("reference",
                "where(nodeIdStrategy.hasId(\"ADDRESS\", addressId, customer_2952383337_address_left"
        );
    }

    @Test
    @DisplayName("With reference via table")
    void referenceViaTable() {
        assertGeneratedContentContains("referenceViaTable",
                ".where(nodeIdStrategy.hasId(\"CITY\", cityId, address_2405880450_city_left"
        );
    }

    @Test
    @DisplayName("With implicit reference from typeName in @nodeId")
    void implicitReference() {
        assertGeneratedContentContains("implicitReference",
                "where(nodeIdStrategy.hasId(\"ADDRESS\", addressId, customer_2952383337_address_left",
                ".leftJoin(customer_2952383337_address_left)"
        );
    }

    @Test
    @DisplayName("With self-reference")
    void selfReference() {
        assertGeneratedContentContains("selfReference",
                ".where(nodeIdStrategy.hasId(\"FILM\", sequelId, film_3747728953_film_left.fields"
        );
    }


    @Test
    @DisplayName("In input type")
    void inputType() {
        assertGeneratedContentContains("inputType",
                ".where(nodeIdStrategy.hasId(\"CUSTOMER\", filter.getCustomerId(), _customer.fields(_customer.getPrimaryKey().getFieldsArray())))"
        );
    }


    @Test
    @DisplayName("In jOOQ record input")
    void jooqRecord() {
        assertGeneratedContentContains("jooqRecord",
                ".where(nodeIdStrategy.hasId(\"CUSTOMER\", filterRecord.getCustomerId(), _customer.fields(_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("In jOOQ record input with reference")
    void jooqRecordWithReference() {
        assertGeneratedContentContains("jooqRecordWithReference",
                "where(nodeIdStrategy.hasId(\"ADDRESS\", filterRecord.getAddressId(), customer_2952383337_address_left",
                ".leftJoin(customer_2952383337_address_left)"
        );
    }

    @Test
    @DisplayName("In java record input")
    void javaRecord() {
        assertGeneratedContentContains("javaRecord", Set.of(CUSTOMER_NODE),
                ".where(nodeIdStrategy.hasId(\"CUSTOMER\", inRecord.getId(), _customer.fields(_customer.getPrimaryKey().getFieldsArray())))"
        );
    }
}
