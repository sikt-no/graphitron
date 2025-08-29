package no.sikt.graphitron.validation;

import no.sikt.graphitron.common.configuration.SchemaComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Node directive validation - Checks run when building the schema for types with node directive")
public class NodeDirectiveTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "nodeDirective";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE, CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Node directive missing node interface")
    void notImplementingNode() {
        assertErrorsContain("notImplementingNode",
                "Problems have been found that prevent code generation:\n" +
                        "Type Customer has the node directive, but does not implement the Node interface."
        );
    }

    @Test
    @DisplayName("Node directive missing table directive")
    void missingTable() {
        assertErrorsContain("missingTable",
                "Problems have been found that prevent code generation:\n" +
                        "Type Customer has the node directive, but is missing the table directive."
        );
    }

    @Test
    @DisplayName("Node directive with invalid keyFields")
    void columnDoesNotExist() {
        assertErrorsContain("columnDoesNotExist",
                "Key column 'WRONG' in node ID for type 'Language' does not exist in table 'LANGUAGE'"
        );
    }

    @Test
    @DisplayName("Key columns matching primary key should not throw error")
    void keyColumnsMatchingPrimaryKey() {
        getProcessedSchema("keyColumnsMatchingPrimaryKey");
    }

    @Test
    @DisplayName("Key columns matching unique key should not throw error")
    void keyColumnsMatchingUniqueKey() {
        getProcessedSchema("keyColumnsMatchingUniqueKey");
    }

    @Test
    @DisplayName("Node directive with keyFields not matching any unique keys")
    void keyColumnsNotMatchingKey() {
        assertErrorsContain("keyColumnsNotMatchingKey",
                "Key columns in node ID for type 'Language' does not match a PK/UK for table 'LANGUAGE'"
        );
    }

    @Test
    @DisplayName("Multiple types with the same table is allowed with node directive")
    void multipleTypesWithSameTable() {
        getProcessedSchema("multipleTypesWithSameTable", NODE_QUERY);
    }
}
