package no.fellesstudentsystem.graphitron_newtestorder.queries;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.JAVA_RECORD_FETCH_QUERY;

@DisplayName("Fetch queries - Queries using input records")
public class FetchWithRecordQueryTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "queries/fetch";

    public FetchWithRecordQueryTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of(JAVA_RECORD_FETCH_QUERY.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Query with input Java records")
    void withInputJavaRecord() {
        assertGeneratedContentMatches("withInputJavaRecord");
    }

    @Test
    @DisplayName("Query with input jOOQ records")
    void withInputJOOQRecord() {
        assertGeneratedContentMatches("withInputJOOQRecord");
    }
}
