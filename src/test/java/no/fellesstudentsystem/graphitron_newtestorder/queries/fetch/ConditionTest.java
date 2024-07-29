package no.fellesstudentsystem.graphitron_newtestorder.queries.fetch;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.RECORD_FETCH_CONDITION;

@DisplayName("Fetch condition queries - Queries that apply custom conditions")
public class ConditionTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RECORD_FETCH_CONDITION, DUMMY_RECORD);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Conditions with input Java records")
    void withInputJavaRecordAndCondition() {
        assertGeneratedContentMatches("withInputJavaRecordAndCondition");
    }

    @Test
    @DisplayName("Conditions with input jOOQ records")
    void withInputJOOQRecordAndCondition() {
        assertGeneratedContentMatches("withInputJOOQRecordAndCondition");
    }

    @Test
    @DisplayName("Conditions with listed inputs")
    void withListedInputConditions() {
        assertGeneratedContentMatches("withListedInputConditions");
    }

    @Test
    @DisplayName("Condition with nested input records")
    void withNestedConditionRecord() {
        assertGeneratedContentMatches("withNestedConditionRecord");
    }

    @Test
    @DisplayName("Condition with input record and pagination")
    void withPaginatedConditionAndRecord() {
        assertGeneratedContentMatches("withPaginatedConditionAndRecord");
    }
}
