package no.sikt.graphitron.validation;

import no.sikt.graphitron.common.configuration.SchemaComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION;

@DisplayName("Entity validation - Checks run for types with @key directive")
public class EntityValidationTest extends ValidationTest {

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "entity";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION);
    }

    @Test
    @DisplayName("Entity without @table directive")
    void noTable() {
        assertErrorsContain("noTable",
                "Entity type Customer must map to a table using the @table directive");
    }

    @Test
    @DisplayName("Entity with nested key")
    void nestedKey() {
        assertErrorsContain("nestedKey",
                "Nested key found in type Customer. This is currently not supported.");
    }

    @Test
    @DisplayName("Key field not found in type")
    void keyNotFound() {
        assertErrorsContain("keyNotFound",
                "Key field nonExistentField was not found in type Customer");
    }

    @Test
    @DisplayName("Key field is a reference")
    void keyIsReference() {
        assertErrorsContain("keyIsReference",
                "Key field address in type Customer is a reference. This is currently not supported");
    }

    @Test
    @DisplayName("Total amount of keys exceeds 22")
    void tooManyKeys() {
        assertErrorsContain("tooManyKeys",
                "Total amount of key columns in type Customer can't be greater than 22.");
    }
}