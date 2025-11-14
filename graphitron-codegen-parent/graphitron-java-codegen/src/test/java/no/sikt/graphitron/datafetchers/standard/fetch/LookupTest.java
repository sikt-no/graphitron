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

@DisplayName("Lookup datafetchers - Datafetchers that look up exact data points based on keys")
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
        assertGeneratedContentContains("integerKey", "_iv_lookupKeys = List.of(ResolverHelpers.formatString(_mi_i))");
    }

    @Test
    @DisplayName("Several flat keys")
    void multipleKeys() {
        assertGeneratedContentContains("multipleKeys", "lookupKeys = List.of(_mi_id0, _mi_id1, _mi_id2)");
    }

    @Test
    @DisplayName("One key and one other field")
    void otherNonKeyField() {
        assertGeneratedContentContains("otherNonKeyField", "lookupKeys = List.of(_mi_id0)", "queryForQuery(_iv_ctx, _mi_id0, _mi_id1,");
    }

    @Test
    @DisplayName("Various key types") // Note that this result has a different order than the input in schema.
    void mixedKeys() {
        assertGeneratedContentContains(
                "mixedKeys", Set.of(DUMMY_INPUT),
                "lookupKeys = List.of(_mi_id, ResolverHelpers.formatString(_mi_i), _mi_in.stream().map(_nit_in -> _nit_in != null ? _nit_in.getId() : null).toList())",
                "queryForQuery(_iv_ctx, _mi_id, _mi_in, _mi_i,"
        );
    }

    @Test
    @DisplayName("Input type key")
    void inputKey() {
        assertGeneratedContentContains(
                "inputKey", Set.of(DUMMY_INPUT),
                "lookupKeys = List.of(_mi_in.stream().map(_nit_in -> _nit_in != null ? _nit_in.getId() : null).toList())"
        );
    }

    @Test
    @Disabled("Does not work. Does not treat outer field as list.")
    @DisplayName("Nested input type key")
    void nestedInputKey() {
        assertGeneratedContentContains(
                "nestedInputKey", Set.of(DUMMY_INPUT),
                "lookupKeys = List.of(_mi_in.stream().map(_iv_it -> _iv_it != null ? _iv_it.getIn().getId() : null).toList())"
        );
    }

    @Test
    @Disabled("Does not work. Does not treat outer field as list.")
    @DisplayName("Nested input type integer key")
    void nestedInputIntegerKey() {
        assertGeneratedContentContains(
                "nestedInputIntegerKey",
                "List.of(ResolverHelpers.formatString(_mi_in.stream().map(_iv_it -> _iv_it != null ? _iv_it.getIn().getId() : null).toList()))"
        );
    }

    @Test
    @Disabled("Does not work. Does not treat outer field as list.")
    @DisplayName("Nested input type key where the directive is placed on a intermediate level")
    void nestedInputKeyMiddle() {
        assertGeneratedContentContains(
                "nestedInputKeyMiddle", Set.of(DUMMY_INPUT),
                "lookupKeys = List.of(_mi_in.stream().map(_iv_it -> _iv_it != null ? _iv_it.getIn().getId() : null).toList())"
        );
    }

    @Test
    @Disabled("Does not work. Assumes outer input is list.")
    @DisplayName("Nested input type key where the list wrapping is placed on a intermediate level")
    void nestedInputKeyWithMiddleList() {
        assertGeneratedContentContains(
                "nestedInputKeyWithMiddleList", Set.of(DUMMY_INPUT),
                "lookupKeys = List.of(_mi_in.getIn().stream().map(_iv_it -> _iv_it != null ? _iv_it.getId() : null).toList())"
        );
    }

    @Test
    @DisplayName("Key inside a listed input type")
    void keyInListedInput() {
        assertGeneratedContentContains(
                "keyInListedInput",
                "lookupKeys = List.of(_mi_in.stream().map(_nit_in -> _nit_in != null ? _nit_in.getId() : null).toList())"
        );
    }

    @Test
    @DisplayName("Listed key inside an input type")
    void listedKeyInInput() {
        assertGeneratedContentContains("listedKeyInInput", "lookupKeys = List.of(_mi_in.getId())");
    }

    @Test
    @DisplayName("Key inside an input type and double key directive")
    void keyInInputWithDuplicateDirective() {
        assertGeneratedContentContains(
                "keyInInputWithDuplicateDirective",
                "lookupKeys = List.of(_mi_in.stream().map(_nit_in -> _nit_in != null ? _nit_in.getId() : null).toList())"
        );
    }

    @Test
    @DisplayName("Key inside a nested input type")
    void keyInNestedInput() {
        assertGeneratedContentContains("keyInNestedInput", "lookupKeys = List.of(_mi_in1.getIn2().getId())");
    }

    @Test
    @DisplayName("Key inside a nested input type where the directive is placed on a intermediate level")
    void keyInNestedInputMiddle() {
        assertGeneratedContentContains("keyInNestedInputMiddle", "lookupKeys = List.of(_mi_in1.getIn2().getId())");
    }

    @Test
    @DisplayName("Integer key inside a nested input type")
    void integerKeyInInput() {
        assertGeneratedContentContains("integerKeyInInput", "lookupKeys = List.of(ResolverHelpers.formatString(_mi_in.getI()))");
    }

    @Test
    @Disabled("Does not work. Maybe it should not?")
    @DisplayName("Key that is not a list")
    void withoutList() {
        assertGeneratedContentContains("withoutList", "lookupKeys = List.of(List.of(_mi_id))");
    }
}
