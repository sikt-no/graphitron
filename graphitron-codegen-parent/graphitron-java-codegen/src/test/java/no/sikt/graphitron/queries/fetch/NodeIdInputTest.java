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
                ".where(nodeIdStrategy.hasId(\"Customer\", customerId, _customer.fields(_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("With reference")
    void reference() {
        assertGeneratedContentContains("reference",
                "where(nodeIdStrategy.hasId(\"Address\", addressId, customer_2952383337_address_left"
        );
    }

    @Test
    @DisplayName("With reference via table")
    void referenceViaTable() {
        assertGeneratedContentContains("referenceViaTable",
                ".where(nodeIdStrategy.hasId(\"City\", cityId, address_2405880450_city_left"
        );
    }

    @Test
    @DisplayName("With implicit reference from typeName in @nodeId")
    void implicitReference() {
        assertGeneratedContentContains("implicitReference",
                "where(nodeIdStrategy.hasId(\"Address\", addressId, customer_2952383337_address_left",
                ".leftJoin(customer_2952383337_address_left)"
        );
    }

    @Test
    @DisplayName("With self-reference")
    void selfReference() {
        assertGeneratedContentContains("selfReference",
                ".where(nodeIdStrategy.hasId(\"Film\", sequelId, film_3747728953_film_left.fields"
        );
    }


    @Test
    @DisplayName("In input type")
    void inputType() {
        assertGeneratedContentContains("inputType",
                ".where(nodeIdStrategy.hasId(\"Customer\", filter.getCustomerId(), _customer.fields(_customer.getPrimaryKey().getFieldsArray())))"
        );
    }


    @Test
    @DisplayName("In jOOQ record input")
    void jooqRecord() {
        assertGeneratedContentContains("jooqRecord",
                ".where(nodeIdStrategy.hasId(\"Customer\", filterRecord, _customer.fields(_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("Key reference in jOOQ record input")
    void jooqRecordReferenceKey() {
        assertGeneratedContentContains("jooqRecordReferenceKey", Set.of(CUSTOMER_NODE),
                ".where(nodeIdStrategy.hasId(\"Language\", filterRecord, _film.ORIGINAL_LANGUAGE_ID)",
                ".from(_film).where" // make sure there's no join
        );
    }

    @Test
    @DisplayName("Reference in jOOQ record input with custom node ID")
    void jooqRecordReferenceWithCustomId() {
        assertGeneratedContentContains("jooqRecordReferenceWithCustomId", Set.of(CUSTOMER_NODE),
                ".where(nodeIdStrategy.hasId(\"A\", filterRecord, _customer.ADDRESS_ID)"
        );
    }

    @Test
    @DisplayName("Reference with FK fields having different order from target node ID")
    void jooqRecordReferenceWithCustomFieldOrder() {
        assertGeneratedContentContains("jooqRecordReferenceWithCustomFieldOrder", Set.of(CUSTOMER_NODE),
                ".where(nodeIdStrategy.hasId(\"A\", filterRecord, _filmactor.ACTOR_LAST_NAME, _filmactor.ACTOR_ID)",
                ".from(_filmactor).where"
        );
    }

    @Test
    @DisplayName("Table reference in jOOQ record input")
    void jooqRecordReferenceTable() {
        assertGeneratedContentContains("jooqRecordReferenceTable", Set.of(CUSTOMER_NODE),
                ".where(nodeIdStrategy.hasId(\"Address\", filterRecord, _customer.ADDRESS_ID"
        );
    }

    @Test
    @DisplayName("Optional reference in jOOQ record should have no null checks")
    void jooqRecordReferenceOptional() {
        assertGeneratedContentContains("jooqRecordReferenceOptional", Set.of(CUSTOMER_NODE),
                "where(nodeIdStrategy",
                "_customer.ADDRESS_ID)).fetch"
        );
    }

    @Test
    @DisplayName("In java record input")
    void javaRecord() {
        assertGeneratedContentContains("javaRecord", Set.of(CUSTOMER_NODE),
                ".where(nodeIdStrategy.hasId(\"CustomerNode\", inRecord.getId(), _customer.fields(_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("Optional in java record input should not skip null checks")
    void javaRecordOptional() {
        assertGeneratedContentContains("javaRecordOptional", Set.of(CUSTOMER_NODE),
                "where(inRecord.getId() != null ? nodeIdStrategy"
        );
    }

    @Test
    @DisplayName("Node ID reference in java record input")
    void javaRecordReference() {
        assertGeneratedContentContains("javaRecordReference", Set.of(CUSTOMER_NODE),
                ".leftJoin(customer_2952383337_address_left)",
                ".where(nodeIdStrategy.hasId(\"Address\", inRecord.getAddressId(), customer_2952383337_address_left.fields("
        );
    }
}
