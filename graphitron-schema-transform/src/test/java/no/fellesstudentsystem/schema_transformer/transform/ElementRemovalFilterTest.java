package no.fellesstudentsystem.schema_transformer.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

public class ElementRemovalFilterTest extends AbstractTest {
    @Test
    @DisplayName("Fields with selected directive are removed, and empty types are deleted")
    void removeField() {
        var path = "removeField";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertDirectives(new ElementRemovalFilter(test, Set.of("D")).getModifiedGraphQLSchema(), expected);
    }

    @Test
    @DisplayName("Types with selected directive are removed")
    void removeType() {
        var path = "removeType";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertDirectives(new ElementRemovalFilter(test, Set.of("D")).getModifiedGraphQLSchema(), expected);
    }

    @Test
    @DisplayName("Enum values with selected directive are removed, and empty enums are deleted")
    void removeEnumValue() {
        var path = "removeEnumValue";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertDirectives(new ElementRemovalFilter(test, Set.of("D")).getModifiedGraphQLSchema(), expected);
    }

    @Test
    @DisplayName("Input fields with selected directive are removed, and empty input types are deleted")
    void removeInputField() {
        var path = "removeInputField";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertDirectives(new ElementRemovalFilter(test, Set.of("D")).getModifiedGraphQLSchema(), expected);
    }

    @Test
    @DisplayName("Interface fields with selected directive are removed, and empty interfaces are deleted")
    void removeInterfaceField() {
        var path = "removeInterfaceField";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertDirectives(new ElementRemovalFilter(test, Set.of("D")).getModifiedGraphQLSchema(), expected);
    }

    @Test
    @DisplayName("Unreachable types are preserved")
    void removeFieldPreserveTypes() {
        var path = "removeFieldPreserveTypes";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(new ElementRemovalFilter(test, Set.of("D")).getModifiedGraphQLSchema(), expected);
    }

    @Test
    @DisplayName("Unreachable unions are preserved")
    void removeFieldPreserveUnions() {
        var path = "removeFieldPreserveUnions";
        var test = makeTestSchema(path);
        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(new ElementRemovalFilter(test, Set.of("D")).getModifiedGraphQLSchema(), expected);
    }
}
