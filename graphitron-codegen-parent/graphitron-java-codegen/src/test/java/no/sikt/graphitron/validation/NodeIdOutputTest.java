package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE_INPUT_TABLE;

@DisplayName("Node directive output validation - Checks run when building the schema for types with node directive")
public class NodeIdOutputTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "nodeId/output";
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Test
    @DisplayName("Type with given name does not exist")
    void typeDoesNotExist() {
        assertErrorsContain(
                () -> getProcessedSchema("typeDoesNotExist"),
                "Type with name 'Address' referenced in the nodeId directive for field 'Customer.addressId' does not exist."
        );
    }

    @Test
    @DisplayName("Type does not have the @node directive")
    void typeDoesNotHaveNodeDirective() {
        assertErrorsContain(
                () -> getProcessedSchema("typeDoesNotHaveNodeDirective"),
                "Referenced type 'Address' referenced in the nodeId directive for field 'Customer.addressId' is missing the necessary node directive."
        );
    }

    @Test
    @DisplayName("Should log error when node ID field has ambiguous implicit node type")
    void ambiguousNodeTypeForNodeId() {
        assertErrorsContain("ambiguousNodeTypeForNodeId",
                "Cannot automatically deduce node type for node ID field 'Customer.id'. " +
                        "Please specify the node type with the typeName parameter in the nodeId directive."
        );
    }

    @Test
    @DisplayName("Should log error when reference node ID field has ambiguous implicit node type")
    void ambiguousNodeTypeForReferenceNodeId() {
        assertErrorsContain("ambiguousNodeTypeForReferenceNodeId",
                "Cannot automatically deduce node type for node ID field 'Customer.addressId'. " +
                        "Please specify the node type with the typeName parameter"
        );
    }

    @Test
    @DisplayName("Should log error when a node ID field without typeName is returned after a mutation.")
    void typeNameOmittedOnMutationReturningNodeId() {
        assertErrorsContain("typeNameOmittedOnMutationReturningNodeId",
                "Cannot automatically deduce node type for node ID field 'Mutation.mutation'. " +
                        "Please specify the node type with the typeName parameter in the nodeId directive."
        );
    }

    @Test
    @DisplayName("@nodeId on non-ID/string field")
    void nonIdOrStringField() {
        assertErrorsContain(
                () -> getProcessedSchema("nonIdOrStringField"),
                "Field 'Customer.id' has nodeId directive, but is not an ID or String field"
        );
    }

    @Test
    @DisplayName("@nodeId on string field")
    void onStringField() {
        getProcessedSchema("onStringField", Set.of(CUSTOMER_NODE));
    }

    @Test
    @DisplayName("nodeId combined with field directive")
    void withField() {
        assertErrorsContain(
                () -> getProcessedSchema("withField"),
                "Field 'Customer.id' has both the 'nodeId' and 'field' directives, which is only supported for node ID fields in Java Record inputs."
        );
    }

    @Test
    @DisplayName("nodeId combined with externalField directive")
    void withExternalField() {
        assertErrorsContain(
                () -> getProcessedSchema("withExternalField"),
                "'Customer.id' has both the 'nodeId' and 'externalField' directives, which is not supported."
        );
    }

    @Test
    @DisplayName("nodeId with invalid reference")
    void invalidReference() {
        assertErrorsContain(
                () -> getProcessedSchema("invalidReference", Set.of(CUSTOMER_NODE)),
                "Error on field \"customerId\" in type \"Category\": No foreign key found between tables \"CATEGORY\" and \"CUSTOMER\""
        );
    }

    @Test
    @DisplayName("nodeId with invalid self-reference")
    void invalidSelfReference() {
        assertErrorsContain(
                () -> getProcessedSchema("invalidSelfReference", Set.of(CUSTOMER_NODE)),
                "Error on field \"parent\" in type \"Customer\": No foreign key found between tables \"CUSTOMER\" and \"CUSTOMER\""
        );
    }

    @Test
    @DisplayName("Returning ID without type wrapping")
    void noWrapping() {
        getProcessedSchema("noWrapping", Set.of(CUSTOMER_NODE_INPUT_TABLE, CUSTOMER_NODE));
    }

    @Test
    @DisplayName("Give richer error message on unknown ID field if NodeIdStrategy is enabled")
    void unknownIdField() {
        assertErrorsContain("unknownIdField", Set.of(CUSTOMER_NODE_INPUT_TABLE),
                "No field with name 'MUTATION' found in table 'CUSTOMER' which may be required by 'Mutation.mutation'. " +
                        "Add nodeId directive if 'Mutation.mutation' is supposed to be a node ID field."
                );
    }
}
