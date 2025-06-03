package no.sikt.graphitron.validation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;

@DisplayName("Node directive input validation - Checks run when building the schema for types with node directive")
public class NodeIdInputTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "nodeId/input";
    }

    @Test
    @DisplayName("Type with given name does not exist")
    void typeDoesNotExist() {
        assertErrorsContain(
                () -> getProcessedSchema("typeDoesNotExist", Set.of(CUSTOMER_NODE)),
                "Type with name 'Address' referenced in the nodeId directive for argument 'addressId' on a field in type 'Query' does not exist."
        );
    }

    @Test
    @DisplayName("Type does not have the @node directive")
    void typeDoesNotHaveNodeDirective() {
        assertErrorsContain(
                () -> getProcessedSchema("typeDoesNotHaveNodeDirective", Set.of(CUSTOMER_NODE)),
                "Referenced type 'Address' referenced in the nodeId directive for argument 'addressId' on a field in type 'Query' is missing the necessary node directive."
        );
    }

    @Test
    @DisplayName("@nodeId on non-ID/string field")
    void nonIdOrStringField() {
        assertErrorsContain(
                () -> getProcessedSchema("nonIdOrStringField", Set.of(CUSTOMER_NODE)),
                "Argument 'id' on a field in type 'Query' has nodeId directive, but is not an ID or String field"
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
                () -> getProcessedSchema("withField", Set.of(CUSTOMER_NODE)),
                "Argument 'id' on a field in type 'Query' has both the 'nodeId' and 'field' directives, which is not supported."
        );
    }

    @Test
    @DisplayName("nodeId combined with externalField directive")
    void withExternalField() {
        assertErrorsContain(
                () -> getProcessedSchema("withExternalField", Set.of(CUSTOMER_NODE)),
                "Argument 'id' on a field in type 'Query' has both the 'nodeId' and 'externalField' directives, which is not supported."
        );
    }

    @Test
    @Disabled("Disabled until GGG-209")
    @DisplayName("nodeID with invalid reference")
    void invalidReference() {
        assertErrorsContain(
                () -> getProcessedSchema("invalidReference"),
                "...."
        );
    }

    @Test
    @Disabled("Disabled until GGG-209")
    @DisplayName("nodeID with invalid self-reference")
    void invalidSelfReference() {
        assertErrorsContain(
                () -> getProcessedSchema("invalidSelfReference", Set.of(CUSTOMER_NODE)),
                "...."
        );
    }
}
