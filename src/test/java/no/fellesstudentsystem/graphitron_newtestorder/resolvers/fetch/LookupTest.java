package no.fellesstudentsystem.graphitron_newtestorder.resolvers.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;

@DisplayName("Lookup resolvers - Resolvers that look up exact data points based on keys")
public class LookupTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/lookup";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_TYPE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Root lookup resolver with one key") // Note that lookup is not defined for non-listed keys.
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Root lookup resolver with an integer key")
    void integerKey() {
        assertGeneratedContentMatches("integerKey");
    }

    @Test
    @DisplayName("Root lookup resolver with several flat keys")
    void multipleKeys() {
        assertGeneratedContentMatches("multipleKeys");
    }

    @Test
    @DisplayName("Root lookup resolver with one key and one other field")
    void otherNonKeyField() {
        assertGeneratedContentMatches("otherNonKeyField");
    }

    @Test
    @DisplayName("Root lookup resolver with various key types") // Note that this result has a different order than the input in schema.
    void mixedKeys() {
        assertGeneratedContentMatches("mixedKeys", DUMMY_INPUT);
    }

    @Test
    @DisplayName("Root lookup resolver with an input type key")
    void inputKey() {
        assertGeneratedContentMatches("inputKey", DUMMY_INPUT);
    }

    @Test
    @Disabled // Does not work. Does not treat outer field as list.
    @DisplayName("Root lookup resolver with a nested input type key")
    void nestedInputKey() {
        assertGeneratedContentMatches("nestedInputKey", DUMMY_INPUT);
    }

    @Test
    @Disabled // Does not work. Does not treat outer field as list.
    @DisplayName("Root lookup resolver with a nested input type integer key")
    void nestedInputIntegerKey() {
        assertGeneratedContentMatches("nestedInputIntegerKey");
    }

    @Test
    @Disabled // Does not work. Does not treat outer field as list.
    @DisplayName("Root lookup resolver with a nested input type key where the directive is placed on a intermediate level")
    void nestedInputKeyMiddle() {
        assertGeneratedContentMatches("nestedInputKeyMiddle", DUMMY_INPUT);
    }

    @Test
    @Disabled // Does not work. Assumes outer input is list.
    @DisplayName("Root lookup resolver with a nested input type key where the list wrapping is placed on a intermediate level")
    void nestedInputKeyWithMiddleList() {
        assertGeneratedContentMatches("nestedInputKeyWithMiddleList", DUMMY_INPUT);
    }

    @Test
    @DisplayName("Root lookup resolver with a key inside an input type")
    void keyInInput() {
        assertGeneratedContentMatches("keyInInput");
    }

    @Test
    @DisplayName("Root lookup resolver with a key inside an input type and double key directive")
    void keyInInputWithDuplicateKey() {
        assertGeneratedContentMatches("keyInInputWithDuplicateKey");
    }

    @Test
    @DisplayName("Root lookup resolver with a key inside a nested input type")
    void keyInNestedInput() {
        assertGeneratedContentMatches("keyInNestedInput");
    }

    @Test
    @DisplayName("Root lookup resolver with a key inside a nested input type where the directive is placed on a intermediate level")
    void keyInNestedInputMiddle() {
        assertGeneratedContentMatches("keyInNestedInputMiddle");
    }

    @Test
    @DisplayName("Root lookup resolver with an integer key inside a nested input type")
    void integerKeyInInput() {
        assertGeneratedContentMatches("integerKeyInInput");
    }

    @Test
    @Disabled // Does not work. Maybe it should not?
    @DisplayName("Root lookup resolver with a key that is not a list")
    void withoutList() {
        assertGeneratedContentMatches("withoutList");
    }

    @Test
    @DisplayName("Lookup resolver that is not on root level with one key") // The rest of the logic should be the same, so no need to make another set of tests for it. Note that this is not fully supported and tested.
    void splitquery() {
        assertGeneratedContentMatches("splitquery", SPLIT_QUERY_WRAPPER);
    }
}
