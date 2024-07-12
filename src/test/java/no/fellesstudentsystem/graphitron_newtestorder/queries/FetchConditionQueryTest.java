package no.fellesstudentsystem.graphitron_newtestorder.queries;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.JAVA_RECORD_FETCH_QUERY;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.RECORD_FETCH_CONDITION;

@DisplayName("Fetch condition queries - Queries that apply custom conditions")
public class FetchConditionQueryTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "queries/fetch";

    public FetchConditionQueryTest() {
        super(SRC_TEST_RESOURCES_PATH, Set.of(RECORD_FETCH_CONDITION.get(), JAVA_RECORD_FETCH_QUERY.get()));
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
}
