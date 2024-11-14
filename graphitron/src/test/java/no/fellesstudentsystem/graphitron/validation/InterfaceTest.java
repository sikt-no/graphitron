package no.fellesstudentsystem.graphitron.validation;

import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.NODE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Interface validation - Checks run when building the schema for interfaces")
public class InterfaceTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "interface";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @Test
    @DisplayName("Interface is used as type for a split query")
    void splitQuery() {
        assertErrorsContain("splitQuery", "interface (Node) returned in non root object. This is not fully supported. Use with care");
    }

    @Test
    @DisplayName("Interface query taking too many arguments")
    void tooManyArguments() {
        assertErrorsContain("tooManyArguments", "Only exactly one input field is currently supported for fields returning interfaces. 'node' has 2 input fields");
    }

    @Test
    @DisplayName("Interface query taking too few arguments")
    void tooFewArguments() {
        assertErrorsContain("tooFewArguments", "Only exactly one input field is currently supported for fields returning interfaces. 'node' has 0 input fields");
    }

    @Test
    @DisplayName("Interface query returning a list")
    void listed() {
        assertErrorsContain("listed", "Generating fields returning collections/lists of interfaces is not supported. 'nodes' must return only one Node");
    }

    @Test
    @DisplayName("Multiple types implementing Node interface and referring to the same table will throw an exception")
    void allTypesUsingNodeInterface() {
        assertErrorsContain("allTypesUsingNodeInterface",
                            "Problems have been found that prevent code generation:\n" +
                            "Multiple types (FilmB, FilmA) implement the Node interface and refer to the same table FILM. This is not supported."
                            );
    }

    @Test
    @DisplayName("Multiple types referring to the same table where only one of these are implementing Node interface are supported")
    void notAllTypesUsingNodeInterface() {
        assertDoesNotThrow(() -> generateFiles("notAllTypesUsingNodeInterface"));
    }
}
