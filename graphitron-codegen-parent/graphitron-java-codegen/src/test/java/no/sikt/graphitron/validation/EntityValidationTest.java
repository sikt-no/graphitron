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
                "Entity type 'Customer' must map to a table using the @table directive");
    }

    @Test
    @DisplayName("Entity with nested key")
    void nestedKey() {
        assertErrorsContain("nestedKey",
                "Nested key(s) found in entity type 'Customer'. This is currently not supported.");
    }

    @Test
    @DisplayName("Key field not found in type")
    void keyNotFound() {
        assertErrorsContain("keyNotFound",
                "Entity Key field 'nonExistentField' was not found in type 'Customer'");
    }

    @Test
    @DisplayName("Key field not found in type suggests similar field")
    void keyNotFoundSimilar() {
        assertErrorsContain("keyNotFoundSimilar",
                "Entity Key field 'cusomerId' was not found in type 'Customer'. Did you mean one of: 'customerId'");
    }

    @Test
    @DisplayName("Key field is a reference")
    void keyIsReference() {
        assertErrorsContain("keyIsReference",
                "Entity Key field 'addressId' in type 'Customer' is a reference. This is currently not supported");
    }
}