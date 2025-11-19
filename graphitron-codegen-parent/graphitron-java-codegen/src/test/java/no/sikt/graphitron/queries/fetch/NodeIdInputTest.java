package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_RECORD;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE_INPUT_TABLE;

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
                ".where(_iv_nodeIdStrategy.hasId(\"Customer\", _mi_customerId, _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("As lookup key")
    void lookupKey() {
        assertGeneratedContentContains("lookupKey",
                "nodeIdStrategy.createId(\"C\", _a_customer.CUSTOMER_ID), queryForQuery_customer(",
                ".where(_mi_customerId.size() > 0 ? _iv_nodeIdStrategy.hasIds"
        );
    }

    @Test
    @DisplayName("With reference")
    void reference() {
        assertGeneratedContentContains("reference",
                "where(_iv_nodeIdStrategy.hasId(\"Address\", _mi_addressId, _a_customer_2168032777_address_left"
        );
    }

    @Test
    @DisplayName("With reference via table")
    void referenceViaTable() {
        assertGeneratedContentContains("referenceViaTable",
                ".where(_iv_nodeIdStrategy.hasId(\"City\", _mi_cityId, _a_address_2138977089_city_left"
        );
    }

    @Test
    @DisplayName("With implicit reference from typeName in @nodeId")
    void implicitReference() {
        assertGeneratedContentContains("implicitReference",
                "where(_iv_nodeIdStrategy.hasId(\"Address\", _mi_addressId, _a_customer_2168032777_address_left",
                ".leftJoin(_a_customer_2168032777_address_left)"
        );
    }

    @Test
    @DisplayName("With self-reference")
    void selfReference() {
        assertGeneratedContentContains("selfReference",
                ".where(_iv_nodeIdStrategy.hasId(\"Film\", _mi_sequelId, _a_film_2185543202_film_left.fields"
        );
    }


    @Test
    @DisplayName("In input type")
    void inputType() {
        assertGeneratedContentContains("inputType",
                ".where(_iv_nodeIdStrategy.hasId(\"Customer\", _mi_filter.getCustomerId(), _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("In jOOQ record input type without nodeId directive")
    void implicitInputType() {
        assertGeneratedContentContains("implicitInputType", Set.of(CUSTOMER_NODE),
                "nodeIdStrategy.hasId(\"CustomerNode\", _mi_filterRecordList.get(_iv_it), _a_customer.fields("
        );
    }

    @Test
    @DisplayName("In jOOQ record input")
    void jooqRecord() {
        assertGeneratedContentContains("jooqRecord", Set.of(CUSTOMER_NODE),
                ".where(_iv_nodeIdStrategy.hasId(\"CustomerNode\", _mi_filterRecord, _a_customer.fields("
        );
    }

    @Test
    @DisplayName("Key reference in jOOQ record input")
    void jooqRecordReferenceKey() {
        assertGeneratedContentContains("jooqRecordReferenceKey", Set.of(CUSTOMER_NODE),
                ".where(_iv_nodeIdStrategy.hasId(\"Language\", _mi_filterRecord, _a_film.ORIGINAL_LANGUAGE_ID)",
                ".from(_a_film).where" // make sure there's no join
        );
    }

    @Test
    @DisplayName("Reference in jOOQ record input with custom node ID")
    void jooqRecordReferenceWithCustomId() {
        assertGeneratedContentContains("jooqRecordReferenceWithCustomId", Set.of(CUSTOMER_NODE),
                ".where(_iv_nodeIdStrategy.hasId(\"A\", _mi_filterRecord, _a_customer.ADDRESS_ID)"
        );
    }

    @Test
    @DisplayName("Reference with FK fields having different order from target node ID")
    void jooqRecordReferenceWithCustomFieldOrder() {
        assertGeneratedContentContains("jooqRecordReferenceWithCustomFieldOrder", Set.of(CUSTOMER_NODE),
                ".where(_iv_nodeIdStrategy.hasId(\"A\", _mi_filterRecord, _a_filmactor.ACTOR_LAST_NAME, _a_filmactor.ACTOR_ID)",
                ".from(_a_filmactor).where"
        );
    }

    @Test
    @DisplayName("Table reference in jOOQ record input")
    void jooqRecordReferenceTable() {
        assertGeneratedContentContains("jooqRecordReferenceTable", Set.of(CUSTOMER_NODE),
                ".where(_iv_nodeIdStrategy.hasId(\"Address\", _mi_filterRecord, _a_customer.ADDRESS_ID"
        );
    }

    @Test
    @DisplayName("Optional reference in jOOQ record should have no null checks")
    void jooqRecordReferenceOptional() {
        assertGeneratedContentContains("jooqRecordReferenceOptional", Set.of(CUSTOMER_NODE),
                "where(_iv_nodeIdStrategy",
                "customer.ADDRESS_ID)).fetch"
        );
    }

    @Test
    @DisplayName("In java record input")
    void javaRecord() {
        assertGeneratedContentContains("javaRecord", Set.of(CUSTOMER_NODE),
                ".where(_iv_nodeIdStrategy.hasId(\"CustomerNode\", _mi_inRecord.getId(), _a_customer.fields("
        );
    }

    @Test
    @DisplayName("Optional in java record input should not skip null checks")
    void javaRecordOptional() {
        assertGeneratedContentContains("javaRecordOptional", Set.of(CUSTOMER_NODE),
                "where(_mi_inRecord.getId() != null ? _iv_nodeIdStrategy"
        );
    }

    @Test
    @DisplayName("Node ID reference in java record input")
    void javaRecordReference() {
        assertGeneratedContentContains("javaRecordReference", Set.of(CUSTOMER_NODE),
                ".leftJoin(_a_customer_2168032777_address_left)",
                ".where(_iv_nodeIdStrategy.hasId(\"Address\", _mi_inRecord.getAddressId(), _a_customer_2168032777_address_left.fields("
        );
    }

    @Test
    @DisplayName("A query returns a type without a table set")
    void typeWithoutTable() {
        assertGeneratedContentContains(
                "typeWithoutTable", Set.of(CUSTOMER_NODE, CUSTOMER_NODE_INPUT_TABLE),
                "customer.getPrimaryKey().getFieldsArray())))" +
                        ".from(_a_customer)" +
                        ".where(_iv_nodeIdStrategy.hasId(\"CustomerNode\"",
                "queryForQuery_customerNoTable(_iv_nodeIdStrategy, _mi_inRecord)).fetchOne("
        );
    }

    @Test
    @DisplayName("Listed input jOOQ records")
    void listedInputJOOQRecord() {
        assertGeneratedContentContains(
                "listedInputJOOQRecord", Set.of(CUSTOMER_NODE, CUSTOMER_NODE_INPUT_TABLE),
                "nodeIdStrategy.hasId(\"CustomerNode\", _mi_inRecordList.get(_iv_it), _a_customer.fields("
        );
    }

    @Test
    @DisplayName("Node ID input on another table object with same table as node type")
    void asInputOnAnotherTableObjectWithSameTable() {
        assertGeneratedContentContains("asInputOnAnotherTableObjectWithSameTable",
                ".from(_a_customer).where(_iv_nodeIdStrategy.hasId(\"C\", _mi_in, _a_customer.fields"
        );
    }

    @Test
    @DisplayName("Strategy appears before method inputs")
    void strategyOrderingInSubqueries() {  // Uses tableMethod to make sure input is propagated to the lowest level.
        assertGeneratedContentContains("strategyOrderingInSubqueries",
                "queryForQuery_customer(_iv_nodeIdStrategy,",
                "queryForQuery_customer(NodeIdStrategy _iv_nodeIdStrategy,",
                "queryForQuery_customer_address(_iv_nodeIdStrategy, ",
                "queryForQuery_customer_address(NodeIdStrategy _iv_nodeIdStrategy,"

        );
    }
}
