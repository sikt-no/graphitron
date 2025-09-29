package no.sikt.graphitron.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Lookup resolvers - Resolvers that look up exact data points based on keys")
public class LookupTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "datafetchers/fetch/lookup";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_TYPE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("One key") // Note that lookup is not defined for non-listed keys.
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("One key not on root level") // The rest of the logic should be the same, so no need to make another set of tests for it. Note that this is not fully supported and tested.
    void splitQuery() {
        assertGeneratedContentMatches("splitQuery", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Integer key")
    void integerKey() {
        assertGeneratedContentContains("integerKey", "keys = List.of(ResolverHelpers.formatString(i))");
    }

    @Test
    @DisplayName("Several flat keys")
    void multipleKeys() {
        assertGeneratedContentContains("multipleKeys", "keys = List.of(id0, id1, id2)");
    }

    @Test
    @DisplayName("One key and one other field")
    void otherNonKeyField() {
        assertGeneratedContentContains("otherNonKeyField", "keys = List.of(id0)", "queryForQuery(ctx, id0, id1,");
    }

    @Test
    @DisplayName("Various key types") // Note that this result has a different order than the input in schema.
    void mixedKeys() {
        assertGeneratedContentContains(
                "mixedKeys", Set.of(DUMMY_INPUT),
                "keys = List.of(id, ResolverHelpers.formatString(i), in.stream().map(itIn -> itIn != null ? itIn.getId() : null).toList())",
                "queryForQuery(ctx, id, in, i,"
        );
    }

    @Test
    @DisplayName("Input type key")
    void inputKey() {
        assertGeneratedContentContains(
                "inputKey", Set.of(DUMMY_INPUT),
                "keys = List.of(in.stream().map(itIn -> itIn != null ? itIn.getId() : null).toList())"
        );
    }

    @Test
    @Disabled("Does not work. Does not treat outer field as list.")
    @DisplayName("Nested input type key")
    void nestedInputKey() {
        assertGeneratedContentContains(
                "nestedInputKey", Set.of(DUMMY_INPUT),
                "keys = List.of(in.stream().map(it -> it != null ? it.getIn().getId() : null).toList())"
        );
    }

    @Test
    @Disabled("Does not work. Does not treat outer field as list.")
    @DisplayName("Nested input type integer key")
    void nestedInputIntegerKey() {
        assertGeneratedContentContains(
                "nestedInputIntegerKey",
                "List.of(ResolverHelpers.formatString(in.stream().map(it -> it != null ? it.getIn().getId() : null).toList()))"
        );
    }

    @Test
    @Disabled("Does not work. Does not treat outer field as list.")
    @DisplayName("Nested input type key where the directive is placed on a intermediate level")
    void nestedInputKeyMiddle() {
        assertGeneratedContentContains(
                "nestedInputKeyMiddle", Set.of(DUMMY_INPUT),
                "keys = List.of(in.stream().map(it -> it != null ? it.getIn().getId() : null).toList())"
        );
    }

    @Test
    @Disabled("Does not work. Assumes outer input is list.")
    @DisplayName("Nested input type key where the list wrapping is placed on a intermediate level")
    void nestedInputKeyWithMiddleList() {
        assertGeneratedContentContains(
                "nestedInputKeyWithMiddleList", Set.of(DUMMY_INPUT),
                "keys = List.of(in.getIn().stream().map(itIn -> itIn != null ? itIn.getId() : null).toList())"
        );
    }

    @Test
    @DisplayName("Key inside a listed input type")
    void keyInListedInput() {
        assertGeneratedContentContains(
                "keyInListedInput",
                "keys = List.of(in.stream().map(itIn -> itIn != null ? itIn.getId() : null).toList())"
        );
    }

    @Test
    @DisplayName("Listed key inside an input type")
    void listedKeyInInput() {
        assertGeneratedContentContains("listedKeyInInput", "keys = List.of(in.getId())");
    }

    @Test
    @DisplayName("Key inside an input type and double key directive")
    void keyInInputWithDuplicateDirective() {
        assertGeneratedContentContains(
                "keyInInputWithDuplicateDirective",
                "keys = List.of(in.stream().map(itIn -> itIn != null ? itIn.getId() : null).toList())"
        );
    }

    @Test
    @DisplayName("Key inside a nested input type")
    void keyInNestedInput() {
        assertGeneratedContentContains("keyInNestedInput", "keys = List.of(in1.getIn2().getId())");
    }

    @Test
    @DisplayName("Key inside a nested input type where the directive is placed on a intermediate level")
    void keyInNestedInputMiddle() {
        assertGeneratedContentContains("keyInNestedInputMiddle", "keys = List.of(in1.getIn2().getId())");
    }

    @Test
    @DisplayName("Integer key inside a nested input type")
    void integerKeyInInput() {
        assertGeneratedContentContains("integerKeyInInput", "keys = List.of(ResolverHelpers.formatString(in.getI()))");
    }

    @Test
    @Disabled("Does not work. Maybe it should not?")
    @DisplayName("Key that is not a list")
    void withoutList() {
        assertGeneratedContentContains("withoutList", "keys = List.of(List.of(id))");
    }
}
